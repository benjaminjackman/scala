/* NSC -- new Scala compiler
 * Copyright 2005-2010 LAMP/EPFL
 * @author  Martin Odersky
 */
// $Id$

package scala.tools.nsc

import java.io.{ File, PrintWriter, StringWriter, Writer }
import java.lang.{ Class, ClassLoader }
import java.net.{ MalformedURLException, URL }
import java.lang.reflect
import reflect.InvocationTargetException

import scala.reflect.Manifest
import scala.collection.mutable
import scala.collection.mutable.{ ListBuffer, HashSet, ArrayBuffer }
import scala.tools.nsc.util.ScalaClassLoader
import ScalaClassLoader.URLClassLoader
import scala.util.control.Exception.{ Catcher, catching, ultimately, unwrapping }

import io.{ PlainFile, VirtualDirectory }
import reporters.{ ConsoleReporter, Reporter }
import symtab.{ Flags, Names }
import util.{ SourceFile, BatchSourceFile, ClassPath }
import scala.util.NameTransformer
import scala.tools.nsc.{ InterpreterResults => IR }
import interpreter._
import Interpreter._

/** <p>
 *    An interpreter for Scala code.
 *  </p>
 *  <p>
 *    The main public entry points are <code>compile()</code>,
 *    <code>interpret()</code>, and <code>bind()</code>.
 *    The <code>compile()</code> method loads a
 *    complete Scala file.  The <code>interpret()</code> method executes one
 *    line of Scala code at the request of the user.  The <code>bind()</code>
 *    method binds an object to a variable that can then be used by later
 *    interpreted code.
 *  </p>
 *  <p>
 *    The overall approach is based on compiling the requested code and then
 *    using a Java classloader and Java reflection to run the code
 *    and access its results.
 *  </p>
 *  <p>  
 *    In more detail, a single compiler instance is used
 *    to accumulate all successfully compiled or interpreted Scala code.  To
 *    "interpret" a line of code, the compiler generates a fresh object that
 *    includes the line of code and which has public member(s) to export
 *    all variables defined by that code.  To extract the result of an
 *    interpreted line to show the user, a second "result object" is created
 *    which imports the variables exported by the above object and then
 *    exports a single member named "scala_repl_result".  To accomodate user expressions
 *    that read from variables or methods defined in previous statements, "import"
 *    statements are used.
 *  </p>
 *  <p>
 *    This interpreter shares the strengths and weaknesses of using the
 *    full compiler-to-Java.  The main strength is that interpreted code
 *    behaves exactly as does compiled code, including running at full speed.
 *    The main weakness is that redefining classes and methods is not handled
 *    properly, because rebinding at the Java level is technically difficult.
 *  </p>
 *
 * @author Moez A. Abdel-Gawad
 * @author Lex Spoon
 */
class Interpreter(val settings: Settings, out: PrintWriter)
{
  /** directory to save .class files to */
  val virtualDirectory = new VirtualDirectory("(memory)", None)

  /** the compiler to compile expressions with */
  val compiler: Global = newCompiler(settings, reporter)

  import compiler.{ Traverser, CompilationUnit, Symbol, Name, Type }
  import compiler.definitions
  import compiler.{ 
    Tree, TermTree, ValOrDefDef, ValDef, DefDef, Assign, ClassDef,
    ModuleDef, Ident, Select, TypeDef, Import, MemberDef, DocDef,
    EmptyTree }
  import compiler.{ nme, newTermName }
  import nme.{ 
    INTERPRETER_VAR_PREFIX, INTERPRETER_SYNTHVAR_PREFIX, INTERPRETER_LINE_PREFIX,
    INTERPRETER_IMPORT_WRAPPER, INTERPRETER_WRAPPER_SUFFIX, USCOREkw
  }
  
  import definitions.{ EmptyPackage, getMember }
  
  /** construct an interpreter that reports to Console */
  def this(settings: Settings) =
    this(settings, new NewLinePrintWriter(new ConsoleWriter, true))

  /** whether to print out result lines */
  private[nsc] var printResults: Boolean = true

  /** Temporarily be quiet */
  def beQuietDuring[T](operation: => T): T = {    
    val wasPrinting = printResults    
    ultimately(printResults = wasPrinting) {
      printResults = false
      operation
    }
  }
  
  /** whether to bind the lastException variable */
  private var bindLastException = true
  
  /** Temporarily stop binding lastException */
  def withoutBindingLastException[T](operation: => T): T = {
    val wasBinding = bindLastException
    ultimately(bindLastException = wasBinding) {
      bindLastException = false
      operation
    }
  }

  /** interpreter settings */
  lazy val isettings = {
    val x = new InterpreterSettings(this)
    quietBind("settings", "scala.tools.nsc.InterpreterSettings", x)
    x
  }
  
  /** Heuristically strip interpreter wrapper prefixes
   *  from an interpreter output string.
   */
  def stripWrapperGunk(str: String): String =
    if (isettings.unwrapStrings) {      
      val wrapregex = """(line[0-9]+\$object[$.])?(\$iw[$.])*"""
      str.replaceAll(wrapregex, "")
    }
    else str

  object reporter extends ConsoleReporter(settings, null, out) {
    override def printMessage(msg: String) {
      out.print(clean(msg) + "\n"); out.flush()
    }
  }

  /** Instantiate a compiler.  Subclasses can override this to
   *  change the compiler class used by this interpreter. */
  protected def newCompiler(settings: Settings, reporter: Reporter) = {
    settings.outputDirs setSingleOutput virtualDirectory    
    new Global(settings, reporter)
  }
  
  /** the compiler's classpath, as URL's */
  val compilerClasspath: List[URL] = {
    def parseURL(s: String): Option[URL] =
      catching(classOf[MalformedURLException]) opt new URL(s)
      
    val classpathPart = 
      ClassPath.expandPath(compiler.settings.classpath.value).map(s => new File(s).toURI.toURL)
    val codebasePart =
      (compiler.settings.Xcodebase.value.split(" ")).toList flatMap parseURL
      
    classpathPart ::: codebasePart
  }

  /* A single class loader is used for all commands interpreted by this Interpreter.
     It would also be possible to create a new class loader for each command
     to interpret.  The advantages of the current approach are:

       - Expressions are only evaluated one time.  This is especially
         significant for I/O, e.g. "val x = Console.readLine"

     The main disadvantage is:

       - Objects, classes, and methods cannot be rebound.  Instead, definitions
         shadow the old ones, and old code objects refer to the old
         definitions.
  */
  private var classLoader: AbstractFileClassLoader = makeClassLoader()
  private def makeClassLoader(): AbstractFileClassLoader = {
    val parent =
      if (parentClassLoader == null)  ScalaClassLoader fromURLs compilerClasspath
      else                            new URLClassLoader(compilerClasspath, parentClassLoader)

    new AbstractFileClassLoader(virtualDirectory, parent)
  }
  private def loadByName(s: String): Class[_] = (classLoader tryToInitializeClass s).get
  private def methodByName(c: Class[_], name: String): reflect.Method =
    c.getMethod(name, classOf[Object])
  
  protected def parentClassLoader: ClassLoader = this.getClass.getClassLoader()  

  // Set the current Java "context" class loader to this interpreter's class loader
  def setContextClassLoader() = classLoader.setAsContext()

  /** the previous requests this interpreter has processed */
  private val prevRequests = new ArrayBuffer[Request]()
  val prevImports = new ListBuffer[Import]()
  
  private def allUsedNames = prevRequests.toList.flatMap(_.usedNames).removeDuplicates
  private def allBoundNames = prevRequests.toList.flatMap(_.boundNames).removeDuplicates
  // private def allImportedNames = prevImports.toList.flatMap(_.importedNames).removeDuplicates
  
  /** Generates names pre0, pre1, etc. via calls to apply method */
  class NameCreator(pre: String) {
    private var x = -1
    var mostRecent: String = null
    
    def apply(): String = { 
      x += 1
      val name = pre + x.toString
      // make sure we don't overwrite their unwisely named res3 etc.
      mostRecent =
        if (allBoundNames exists (_.toString == name)) apply()
        else name
      
      mostRecent
    }
    def reset(): Unit = x = -1
    def didGenerate(name: String) =
      (name startsWith pre) && ((name drop pre.length) forall (_.isDigit))
  }

  /** allocate a fresh line name */
  private val lineNameCreator = new NameCreator(INTERPRETER_LINE_PREFIX)
  
  /** allocate a fresh var name */
  private val varNameCreator = new NameCreator(INTERPRETER_VAR_PREFIX)
  
  /** allocate a fresh internal variable name */
  private def synthVarNameCreator = new NameCreator(INTERPRETER_SYNTHVAR_PREFIX)

  /** Check if a name looks like it was generated by varNameCreator */
  private def isGeneratedVarName(name: String): Boolean = varNameCreator didGenerate name  
  private def isSynthVarName(name: String): Boolean = synthVarNameCreator didGenerate name

  /** generate a string using a routine that wants to write on a stream */
  private def stringFrom(writer: PrintWriter => Unit): String = {
    val stringWriter = new StringWriter()
    val stream = new NewLinePrintWriter(stringWriter)
    writer(stream)
    stream.close
    stringWriter.toString
  }

  /** Truncate a string if it is longer than settings.maxPrintString */
  private def truncPrintString(str: String): String = {
    val maxpr = isettings.maxPrintString
    val trailer = "..."
    
    if (maxpr <= 0 || str.length <= maxpr) str
    else str.substring(0, maxpr-3) + trailer
  }

  /** Clean up a string for output */
  private def clean(str: String) = truncPrintString(stripWrapperGunk(str))

  /** Indent some code by the width of the scala> prompt.
   *  This way, compiler error messages read better.
   */
  private final val spaces = List.fill(7)(" ").mkString
  def indentCode(code: String) = {
    /** Heuristic to avoid indenting and thereby corrupting """-strings and XML literals. */
    val noIndent = (code contains "\n") && (List("\"\"\"", "</", "/>") exists (code contains _))
    stringFrom(str =>
      for (line <- code.lines) {
        if (!noIndent)
          str.print(spaces)

        str.print(line + "\n")
        str.flush()
      })
  }
  def indentString(s: String) = s split "\n" map (spaces + _ + "\n") mkString

  implicit def name2string(name: Name) = name.toString

  /** Compute imports that allow definitions from previous
   *  requests to be visible in a new request.  Returns
   *  three pieces of related code:
   *
   *  1. An initial code fragment that should go before
   *  the code of the new request.
   *
   *  2. A code fragment that should go after the code
   *  of the new request.
   *
   *  3. An access path which can be traverested to access
   *  any bindings inside code wrapped by #1 and #2 .
   *
   * The argument is a set of Names that need to be imported.
   *
   * Limitations: This method is not as precise as it could be.
   * (1) It does not process wildcard imports to see what exactly
   * they import.
   * (2) If it imports any names from a request, it imports all
   * of them, which is not really necessary.
   * (3) It imports multiple same-named implicits, but only the
   * last one imported is actually usable.
   */
  private case class ComputedImports(prepend: String, append: String, access: String)
  private def importsCode(wanted: Set[Name]): ComputedImports = {
    /** Narrow down the list of requests from which imports 
     *  should be taken.  Removes requests which cannot contribute
     *  useful imports for the specified set of wanted names.
     */
    case class ReqAndHandler(req: Request, handler: MemberHandler)
    def reqsToUse: List[ReqAndHandler] = {
      /** Loop through a list of MemberHandlers and select which ones to keep.
        * 'wanted' is the set of names that need to be imported.
       */
      def select(reqs: List[ReqAndHandler], wanted: Set[Name]): List[ReqAndHandler] = {
        val isWanted = wanted contains _
        def keepHandler(handler: MemberHandler): Boolean = {
          import handler._
          // Single symbol imports might be implicits! See bug #1752.  Rather than
          // try to finesse this, we will mimic all imports for now.
          def isImport = handler.isInstanceOf[ImportHandler]
          definesImplicit || isImport || (importedNames ++ boundNames).exists(isWanted)
        }
                   
        reqs match {
          case Nil                                    => Nil
          case rh :: rest if !keepHandler(rh.handler) => select(rest, wanted)
          case rh :: rest                             =>
            import rh.handler._
            val newWanted = wanted ++ usedNames -- boundNames -- importedNames
            rh :: select(rest, newWanted)
        }
      }

      val rhpairs = for {
        req <- prevRequests.toList.reverse
        handler <- req.handlers
      } yield ReqAndHandler(req, handler)

      select(rhpairs, wanted).reverse
    }

    val code, trailingBraces, accessPath = new StringBuffer
    val currentImps = mutable.Set.empty[Name]

    // add code for a new object to hold some imports
    def addWrapper() {
      val impname = INTERPRETER_IMPORT_WRAPPER
      code append "object %s {\n".format(impname)
      trailingBraces append "}\n"
      accessPath append ("." + impname)

      currentImps.clear
    }

    addWrapper()

    // loop through previous requests, adding imports for each one
    for (ReqAndHandler(req, handler) <- reqsToUse) {
      import handler._
      // If the user entered an import, then just use it; add an import wrapping
      // level if the import might conflict with some other import
      if (importsWildcard || currentImps.exists(importedNames.contains))
        addWrapper()
      
      if (member.isInstanceOf[Import])
        code append (member.toString + "\n")

      // give wildcard imports a import wrapper all to their own
      if (importsWildcard)  addWrapper()  
      else                  currentImps ++= importedNames

      // For other requests, import each bound variable.
      // import them explicitly instead of with _, so that
      // ambiguity errors will not be generated. Also, quote 
      // the name of the variable, so that we don't need to 
      // handle quoting keywords separately. 
      for (imv <- boundNames) {
        if (currentImps contains imv) addWrapper()
        
        code append ("import " + req.fullPath(imv))
        currentImps += imv
      }
    }

    // add one extra wrapper, to prevent warnings in the common case of
    // redefining the value bound in the last interpreter request.
    addWrapper()
    ComputedImports(code.toString, trailingBraces.toString, accessPath.toString)
  }

  /** Parse a line into a sequence of trees. Returns None if the input is incomplete. */
  private def parse(line: String): Option[List[Tree]] = {
    var justNeedsMore = false
    reporter.withIncompleteHandler((pos,msg) => {justNeedsMore = true}) {
      // simple parse: just parse it, nothing else
      def simpleParse(code: String): List[Tree] = {
        reporter.reset
        val unit = new CompilationUnit(new BatchSourceFile("<console>", code))
        val scanner = new compiler.syntaxAnalyzer.UnitParser(unit)
        
        scanner.templateStatSeq(false)._2
      }
      val trees = simpleParse(line)
      
      if (reporter.hasErrors)   Some(Nil)  // the result did not parse, so stop
      else if (justNeedsMore)   None
      else                      Some(trees)
    }
  }
  
  /** For :power - create trees and type aliases from code snippets. */
  def mkTree(code: String): Tree = mkTrees(code).headOption getOrElse EmptyTree
  def mkTrees(code: String): List[Tree] = parse(code) getOrElse Nil
  def mkType(name: String, what: String) = interpret("type " + name + " = " + what)

  /** Compile an nsc SourceFile.  Returns true if there are
   *  no compilation errors, or false othrewise.
   */
  def compileSources(sources: SourceFile*): Boolean = {
    reporter.reset
    new compiler.Run() compileSources sources.toList
    !reporter.hasErrors
  }

  /** Compile a string.  Returns true if there are no
   *  compilation errors, or false otherwise.
   */
  def compileString(code: String): Boolean =
    compileSources(new BatchSourceFile("<script>", code))

  /** Build a request from the user. <code>trees</code> is <code>line</code>
   *  after being parsed.
   */
  private def buildRequest(line: String, lineName: String, trees: List[Tree]): Request =
    new Request(line, lineName, trees)

  private def chooseHandler(member: Tree): MemberHandler = member match {
    case member: DefDef               => new DefHandler(member)
    case member: ValDef               => new ValHandler(member)
    case member@Assign(Ident(_), _)   => new AssignHandler(member)
    case member: ModuleDef            => new ModuleHandler(member)
    case member: ClassDef             => new ClassHandler(member)
    case member: TypeDef              => new TypeAliasHandler(member)
    case member: Import               => new ImportHandler(member)
    case DocDef(_, documented)        => chooseHandler(documented)
    case member                       => new GenericHandler(member)
  }
  
  private def requestFromLine(line: String): Either[IR.Result, Request] = {
    // initialize the compiler
    if (prevRequests.isEmpty) new compiler.Run()

    // parse
    val trees = parse(indentCode(line)) match {
      case None         => return Left(IR.Incomplete)
      case Some(Nil)    => return Left(IR.Error) // parse error or empty input
      case Some(trees)  => trees
    }

    // Treat a single bare expression specially. This is necessary due to it being hard to
    // modify code at a textual level, and it being hard to submit an AST to the compiler.
    if (trees.size == 1) trees.head match {
      case _:Assign                         => // we don't want to include assignments
      case _:TermTree | _:Ident | _:Select  =>
        return requestFromLine("val %s =\n%s".format(varNameCreator(), line))
      case _                                =>
    }
        
    // figure out what kind of request
    Right(buildRequest(line, lineNameCreator(), trees))
  }

  /** <p>
   *    Interpret one line of input.  All feedback, including parse errors
   *    and evaluation results, are printed via the supplied compiler's 
   *    reporter.  Values defined are available for future interpreted
   *    strings.
   *  </p>
   *  <p>
   *    The return value is whether the line was interpreter successfully,
   *    e.g. that there were no parse errors.
   *  </p>
   *
   *  @param line ...
   *  @return     ...
   */
  def interpret(line: String): IR.Result = {
    val req = requestFromLine(line) match {
      case Left(result) => return result
      case Right(req)   => req
    }

    // null is a disallowed statement type; otherwise compile and fail if false (implying e.g. a type error)
    if (req == null || !req.compile)
      return IR.Error
        
    val (result, succeeded) = req.loadAndRun
    if (printResults || !succeeded)
      out print clean(result)

    if (succeeded) {
      prevRequests += req     // book-keeping
      IR.Success
    }
    else IR.Error
  }

  /** A name creator used for objects created by <code>bind()</code>. */
  private val newBinder = new NameCreator("binder")

  /** Bind a specified name to a specified value.  The name may
   *  later be used by expressions passed to interpret.
   *
   *  @param name      the variable name to bind
   *  @param boundType the type of the variable, as a string
   *  @param value     the object value to bind to it
   *  @return          an indication of whether the binding succeeded
   */
  def bind(name: String, boundType: String, value: Any): IR.Result = {
    val binderName = newBinder()    // "binder" + binderNum()

    compileString("""
      | object %s {
      |   var value: %s = _
      |   def set(x: Any) = value = x.asInstanceOf[%s]
      | }
    """.stripMargin.format(binderName, boundType, boundType))

    val binderObject = loadByName(binderName)
    val setterMethod = methodByName(binderObject, "set")
    
    setterMethod.invoke(null, value.asInstanceOf[AnyRef])
    interpret("val %s = %s.value".format(name, binderName))
  }
  
  def quietBind(name: String, boundType: String, value: Any): IR.Result = 
    beQuietDuring { bind(name, boundType, value) }

  /** Reset this interpreter, forgetting all user-specified requests. */
  def reset() {
    virtualDirectory.clear
    classLoader = makeClassLoader
    lineNameCreator.reset()
    varNameCreator.reset()
    prevRequests.clear
  }

  /** <p>
   *    This instance is no longer needed, so release any resources
   *    it is using.  The reporter's output gets flushed.
   *  </p>
   */
  def close() {
    reporter.flush
  }

  /** A traverser that finds all mentioned identifiers, i.e. things
   *  that need to be imported.  It might return extra names.
   */
  private class ImportVarsTraverser extends Traverser {
    val importVars = new HashSet[Name]()

    override def traverse(ast: Tree) = ast match {
      // XXX this is obviously inadequate but it's going to require some effort
      // to get right.
      case Ident(name) if !(name.toString startsWith "x$")  => importVars += name
      case _                                                => super.traverse(ast)
    }
  }

  /** Class to handle one member among all the members included
   *  in a single interpreter request.
   */
  private sealed abstract class MemberHandler(val member: Tree) {
    lazy val usedNames: List[Name] = {
      val ivt = new ImportVarsTraverser()
      ivt traverse member
      ivt.importVars.toList
    }
    def boundNames: List[Name] = Nil
    def valAndVarNames: List[Name] = Nil
    def defNames: List[Name] = Nil
    val importsWildcard = false
    val importedNames: Seq[Name] = Nil
    val definesImplicit = member match {
      case tree: MemberDef  => tree.mods hasFlag Flags.IMPLICIT
      case _                => false
    }
    
    def generatesValue: Option[Name] = None

    def extraCodeToEvaluate(req: Request, code: PrintWriter) { }
    def resultExtractionCode(req: Request, code: PrintWriter) { }

    override def toString = "%s(usedNames = %s)".format(this.getClass, usedNames)
  }

  private class GenericHandler(member: Tree) extends MemberHandler(member)
  
  private class ValHandler(member: ValDef) extends MemberHandler(member) {
    lazy val ValDef(mods, vname, _, _) = member    
    lazy val prettyName = NameTransformer.decode(vname)
    lazy val isLazy = mods hasFlag Flags.LAZY
    
    override lazy val boundNames = List(vname)
    override def valAndVarNames = boundNames
    override def generatesValue = Some(vname)
    
    override def resultExtractionCode(req: Request, code: PrintWriter) {
      val isInternal = isGeneratedVarName(vname) && req.typeOfEnc(vname) == "Unit"
      if (!mods.isPublic || isInternal) return
      
      lazy val extractor = """
        | {
        |    val s = scala.runtime.ScalaRunTime.stringOf(%s)
        |    val nl = if (s.contains('\n')) "\n" else ""
        |    nl + s + "\n"
        | }
      """.stripMargin.format(req fullPath vname)
      
      // if this is a lazy val we avoid evaluating it here
      val resultString = if (isLazy) codegenln(false, "<lazy>") else extractor
      val codeToPrint = 
        """ + "%s: %s = " + %s""" .
        format(prettyName, string2code(req.typeOf(vname)), resultString)

      code print codeToPrint
    }
  }

  private class DefHandler(defDef: DefDef) extends MemberHandler(defDef) {
    lazy val DefDef(mods, name, _, _, _, _) = defDef
    override lazy val boundNames = List(name)
    override def defNames = boundNames

    override def resultExtractionCode(req: Request, code: PrintWriter) =
      if (mods.isPublic) code print codegenln(name, ": ", req.typeOf(name))
  }

  private class AssignHandler(member: Assign) extends MemberHandler(member) {
    val lhs = member.lhs.asInstanceOf[Ident] // an unfortunate limitation
    val helperName = newTermName(synthVarNameCreator())
    override val valAndVarNames = List(helperName)
    override def generatesValue = Some(helperName)

    override def extraCodeToEvaluate(req: Request, code: PrintWriter) =
      code println """val %s = %s""".format(helperName, lhs)

    /** Print out lhs instead of the generated varName */
    override def resultExtractionCode(req: Request, code: PrintWriter) {
      val lhsType = string2code(req typeOfEnc helperName)
      val res = string2code(req fullPath helperName)
      val codeToPrint = """ + "%s: %s = " + %s + "\n" """.format(lhs, lhsType, res)
          
      code println codeToPrint
    }
  }

  private class ModuleHandler(module: ModuleDef) extends MemberHandler(module) {
    lazy val ModuleDef(mods, name, _) = module
    override lazy val boundNames = List(name)
    override def generatesValue = Some(name)

    override def resultExtractionCode(req: Request, code: PrintWriter) =
      code println codegenln("defined module ", name)
  }

  private class ClassHandler(classdef: ClassDef) extends MemberHandler(classdef) {
    lazy val ClassDef(mods, name, _, _) = classdef
    override lazy val boundNames = 
      name :: (if (mods hasFlag Flags.CASE) List(name.toTermName) else Nil)
    
    override def resultExtractionCode(req: Request, code: PrintWriter) =
      code print codegenln("defined %s %s".format(classdef.keyword, name))
  }

  private class TypeAliasHandler(typeDef: TypeDef) extends MemberHandler(typeDef) {
    lazy val TypeDef(mods, name, _, _) = typeDef
    def isAlias() = mods.isPublic && compiler.treeInfo.isAliasTypeDef(typeDef)
    override lazy val boundNames = if (isAlias) List(name) else Nil

    override def resultExtractionCode(req: Request, code: PrintWriter) =
      code println codegenln("defined type alias ", name)
  }

  private class ImportHandler(imp: Import) extends MemberHandler(imp) {
    /** Whether this import includes a wildcard import */
    override val importsWildcard = imp.selectors.map(_.name) contains USCOREkw

    /** The individual names imported by this statement */
    override val importedNames: Seq[Name] = for {
      sel <- imp.selectors
      if (sel.rename != null && sel.rename != USCOREkw)
      name <- List(sel.rename.toTypeName, sel.rename.toTermName)
    }
    yield name

    // record the import
    prevImports += imp
    
    override def resultExtractionCode(req: Request, code: PrintWriter) =
      code println codegenln(imp.toString)
  }

  /** One line of code submitted by the user for interpretation */
  private class Request(val line: String, val lineName: String, val trees: List[Tree]) {
    // val trees = parse(line) getOrElse Nil

    /** name to use for the object that will compute "line" */
    def objectName = lineName + INTERPRETER_WRAPPER_SUFFIX

    /** name of the object that retrieves the result from the above object */
    def resultObjectName = "RequestResult$" + objectName

    /** handlers for each tree in this request */
    val handlers: List[MemberHandler] = trees map chooseHandler

    /** all (public) names defined by these statements */
    val boundNames = handlers flatMap (_.boundNames)

    /** list of names used by this expression */
    val usedNames: List[Name] = handlers.flatMap(_.usedNames)

    /** Code to import bound names from previous lines - accessPath is code to
      * append to objectName to access anything bound by request. */
    val ComputedImports(importsPreamble, importsTrailer, accessPath) =
      importsCode(Set.empty ++ usedNames)

    /** Code to access a variable with the specified name */
    def fullPath(vname: String): String = "%s.`%s`\n".format(objectName + accessPath, vname)

    /** Code to access a variable with the specified name */
    def fullPath(vname: Name): String = fullPath(vname.toString)

    /** the line of code to compute */
    def toCompute = line

    /** generate the source code for the object that computes this request */
    def objectSourceCode: String = stringFrom { code => 
      // whitespace compatible with interpreter.scala
      val preamble = """object %s {
        |   %s%s
      """.stripMargin.format(objectName, importsPreamble, indentCode(toCompute))     
      val postamble = importsTrailer + "; }"

      code println preamble
      handlers foreach { _.extraCodeToEvaluate(this, code) }
      code println postamble
    }

    /** generate source code for the object that retrieves the result
        from objectSourceCode */
    def resultObjectSourceCode: String = stringFrom { code =>
      /** We only want to generate this code when the result
       *  is a value which can be referred to as-is.
       */      
      val valueExtractor = handlers.last.generatesValue match {
        case Some(vname) if typeOf contains vname =>
          """
          | lazy val scala_repl_value = {
          |   scala_repl_result // make sure that's run
          |   %s
          | }""".stripMargin.format(fullPath(vname))
        case _  => ""
      }
      
      val preamble = """
      | object %s {
      |   %s
      |   val scala_repl_result: String = {
      |     %s    // evaluate object to make sure constructor is run
      |     (""   // an initial "" so later code can uniformly be: + etc
      """.stripMargin.format(resultObjectName, valueExtractor, objectName + accessPath)
      
      val postamble = """
      |     )
      |   }
      | }
      """.stripMargin

      code println preamble
      handlers foreach { _.resultExtractionCode(this, code) }
      code println postamble
    }

    lazy val objRun = {
      val x = new compiler.Run()
      // compile the object containing the user's code
      x.compileSources(List(new BatchSourceFile("<console>", objectSourceCode)))
      x
    }
    
    lazy val extractionObjectRun = {
      val x = new compiler.Run()
      // compile the result-extraction object
      x.compileSources(List(new BatchSourceFile("<console>", resultObjectSourceCode)))
      x
    }
    
    def extractionValue(): Option[AnyRef] = {
      // ensure it has run
      extractionObjectRun
      
      catching(classOf[Exception]) opt {
        // load it and retrieve the value
        val result: Class[_] = loadByName(resultObjectName)
        
        result getMethod "scala_repl_value" invoke result
      }
    }

    /** Compile the object file.  Returns whether the compilation succeeded.
     *  If all goes well, the "types" map is computed. */
    def compile(): Boolean = {
      // error counting is wrong, hence interpreter may overlook failure - so we reset
      reporter.reset

      // compile the main object
      objRun
      
      // bail on error
      if (reporter.hasErrors)
        return false

      // extract and remember types 
      typeOf

      // compile the result-extraction object
      extractionObjectRun

      // success
      !reporter.hasErrors
    }

    def valAndVarNames = handlers flatMap { _.valAndVarNames }
    def defNames = handlers flatMap { _.defNames }
    def atNextPhase[T](op: => T): T = compiler.atPhase(objRun.typerPhase.next)(op)

    /** The outermost wrapper object */
    lazy val outerResObjSym: Symbol = getMember(EmptyPackage, newTermName(objectName))

    /** The innermost object inside the wrapper, found by
      * following accessPath into the outer one. */
    lazy val resObjSym =
      accessPath.split("\\.").foldLeft(outerResObjSym) { (sym, name) =>
        if (name == "") sym else
        atNextPhase(sym.info member newTermName(name))
      }

    /* typeOf lookup with encoding */
    def typeOfEnc(vname: Name) = typeOf(compiler encode vname)

    /** Types of variables defined by this request. */
    lazy val typeOf: Map[Name, String] = {
      def getTypes(names: List[Name], nameMap: Name => Name): Map[Name, String] = {
        names.foldLeft(Map.empty[Name, String]) { (map, name) =>
          val rawType = atNextPhase(resObjSym.info.member(name).tpe)
          // the types are all =>T; remove the =>
          val cleanedType = rawType match { 
            case compiler.PolyType(Nil, rt) => rt
            case rawType => rawType
          }

          map + (name -> atNextPhase(cleanedType.toString))
        }
      }

      getTypes(valAndVarNames, nme.getterToLocal(_)) ++ getTypes(defNames, identity)
    }

    /** load and run the code using reflection */
    def loadAndRun: (String, Boolean) = {
      val resultObject: Class[_] = loadByName(resultObjectName)
      val resultValMethod: reflect.Method = resultObject getMethod "scala_repl_result"
      // XXX if wrapperExceptions isn't type-annotated we crash scalac
      val wrapperExceptions: List[Class[_ <: Throwable]] =
        List(classOf[InvocationTargetException], classOf[ExceptionInInitializerError])
      
      /** We turn off the binding to accomodate ticket #2817 */
      def onErr: Catcher[(String, Boolean)] = {
        case t: Throwable if bindLastException =>
          withoutBindingLastException {
            quietBind("lastException", "java.lang.Throwable", t)
            (stringFrom(t.printStackTrace(_)), false)
          }
      }
      
      catching(onErr) {
        unwrapping(wrapperExceptions: _*) {
          (resultValMethod.invoke(resultObject).toString, true)
        }
      }
    }
  }
  
  /** These methods are exposed so REPL commands can access them.
   *  The command infrastructure is in InterpreterLoop.
   */
  def dumpState(xs: List[String]): String = {
    // println("Imports for " + req + " => " + req.importsPreamble)
    // req.handlers foreach { h => println("Handler " + h + " used names: " + h.usedNames) }
    // req.trees foreach { x => println("Tree: " + x) }
    // xs foreach { x => println("membersOfIdentifier(" + x + ") = " + membersOfIdentifier(x)) }
    List(
      "allUsedNames = " + allUsedNames,
      "allBoundNames = " + allBoundNames,
      prevRequests.toList.map(req => "  \"" + req.line + "\" => " + req.objectSourceCode)
    ).mkString("", "\n", "\n")
  }
      
  // very simple right now, will get more interesting
  def dumpTrees(xs: List[String]): String = {
    val treestrs = (xs map requestForIdent).flatten flatMap (_.trees)

    if (treestrs.isEmpty) "No trees found."
    else treestrs.map(t => t.toString + " (" + t.getClass.getSimpleName + ")\n").mkString
  }
      
  def powerUser(): String = {
    beQuietDuring {
      this.bind("interpreter", "scala.tools.nsc.Interpreter", this)
      this.bind("global", "scala.tools.nsc.Global", compiler)
      interpret("""import interpreter.{ mkType, mkTree, mkTrees, eval }""")
    }
    
    """** Power User mode enabled - BEEP BOOP      **
      |** New vals! Try interpreter, global        **
      |** New cmds! :help to discover them         **
      |** New defs! Give these a whirl:            **
      |**   mkType("Fn", "(String, Int) => Int")   **
      |**   mkTree("def f(x: Int, y: Int) = x+y")  **""".stripMargin
  }
  
  def nameOfIdent(line: String): Option[Name] = {
    parse(line) match {
      case Some(List(Ident(x))) => Some(x)
      case _                    => None
    }
  }
  
  /** Returns the name of the most recent interpreter result.
   *  Mostly this exists so you can conveniently invoke methods on
   *  the previous result.
   */
  def mostRecentVar: String =    
    prevRequests.last.handlers.last.member match {
      case x: ValOrDefDef           => x.name
      case Assign(Ident(name), _)   => name
      case ModuleDef(_, name, _)    => name
      case _                        => varNameCreator.mostRecent
    }
  
  private def requestForName(name: Name): Option[Request] = {
    for (req <- prevRequests.toList.reverse) {
      if (req.handlers.exists(_.boundNames contains name))
        return Some(req)
    }
    None
  }
  private def requestForIdent(line: String): Option[Request] =
    nameOfIdent(line) flatMap requestForName
  
  private def mkValDef(line: String, name: String = varNameCreator()) =
    (name, "val %s = %s".format(name, line))

  // private def inCompletion(s: String) = "scala.tools.nsc.interpreter.Completion." + s
  private def inCompletion(s: String) = classOf[Completion].getName + "." + s
  private def methodsCode(name: String) = inCompletion("methodsOf(" + name + ")")
  private def isSpecialCode(name: String) = inCompletion("isSpecial(" + name + ")")
  private def selfDefinedMembersCode(name: String) = inCompletion("selfDefinedMembers(" + name + ")")

  private def getOriginalName(name: String): String =
    nme.originalName(newTermName(name)).toString

  case class InterpreterEvalException(msg: String) extends Exception(msg)
  def evalError(msg: String) = throw InterpreterEvalException(msg)
  
  /** The user-facing eval in :power mode wraps an Option.
   */
  def eval[T: Manifest](line: String): Option[T] =
    try Some(evalExpr[T](line))
    catch { case InterpreterEvalException(msg) => println(indentString(msg)) ; None }

  def evalExpr[T: Manifest](line: String): T = {
    // Nothing means the type could not be inferred.
    if (manifest[T] eq Manifest.Nothing)
      evalError("Could not infer type: try 'eval[SomeType](%s)' instead".format(line))
    
    val lhs = varNameCreator()
    beQuietDuring { interpret("val " + lhs + " = { " + line + " } ") }
    
    // TODO - can we meaningfully compare the inferred type T with
    //   the internal compiler Type assigned to lhs?
    // def assignedType = prevRequests.last.typeOf(newTermName(lhs))

    val req = requestFromLine(lhs) match {
      case Left(result) => evalError(result.toString)
      case Right(req)   => req
    }
    if (req == null || !req.compile || req.handlers.size != 1)
      evalError("Eval error.")
      
    try req.extractionValue.get.asInstanceOf[T] catch {
      case e: Exception => evalError(e.getMessage)
    }
  }
  
  def interpretExpr[T: Manifest](code: String): Option[T] = beQuietDuring {
    interpret(code) match {
      case IR.Success =>
        try Some(prevRequests.last.extractionValue.get.asInstanceOf[T])
        catch { case e: Exception => println(e) ; None }  
      case _ => None
    }
  }
  
  private def memberListFor(name: String): List[String] = {
    import NameTransformer.{ decode, encode }   // e.g. $plus$plus => ++
    
    /** Give objects a chance to define their own members. */
    val special = evalExpr[Option[List[String]]](selfDefinedMembersCode(name))
    
    /** Failing that, use reflection. */
    special getOrElse evalExpr[List[String]](methodsCode(name)) map (x => decode(getOriginalName(x)))
  }

  /** The main entry point for tab-completion.  When the user types x.<tab>
   *  this method is called with "x" as an argument, and it discovers the
   *  fields and methods of x via reflection and returns their names to jline.
   */
  def membersOfIdentifier(line: String): List[String] =    
    beQuietDuring {
      for (name <- nameOfIdent(line) ; req <- requestForName(name)) yield {
        memberListFor(name) filterNot Completion.shouldHide removeDuplicates
      }
    } getOrElse Nil
  
  /** Another entry point for tab-completion, ids in scope */
  def unqualifiedIds(): List[String] =
    allBoundNames map (_.toString) filterNot isSynthVarName
   
  /** For static/object method completion */ 
  def getClassObject(path: String): Option[Class[_]] = classLoader tryToLoadClass path
  
  /** Parse the ScalaSig to find type aliases */
  def aliasForType(path: String) = ByteCode.aliasForType(path)
  
  /** Artificial object */
  class ReplVars extends Completion.Special {
    def tabCompletions() = unqualifiedIds()
  }
  def replVarsObject() = new ReplVars()

  // Coming soon  
  // implicit def string2liftedcode(s: String): LiftedCode = new LiftedCode(s)
  // case class LiftedCode(code: String) {
  //   val lifted: String = {
  //     beQuietDuring { interpret(code) }
  //     eval2[String]("({ " + code + " }).toString")
  //   }
  //   def >> : String = lifted
  // }
  
  // debugging
  private var debuggingOutput = false
  def DBG(s: String) = if (debuggingOutput) out println s else ()  
}

/** Utility methods for the Interpreter. */
object Interpreter {
  
  object DebugParam {
    implicit def tuple2debugparam[T](x: (String, T))(implicit m: scala.reflect.Manifest[T]): DebugParam[T] =
      DebugParam(x._1, x._2)
    
    implicit def any2debugparam[T](x: T)(implicit m: scala.reflect.Manifest[T]): DebugParam[T] =
      DebugParam("p" + getCount(), x)
    
    private var counter = 0 
    def getCount() = { counter += 1; counter }
  }
  case class DebugParam[T](name: String, param: T)(implicit m: scala.reflect.Manifest[T]) {
    val manifest = m
    val typeStr = {
      val str = manifest.toString
      // I'm sure there are more to be discovered...
      val regexp1 = """(.*?)\[(.*)\]""".r
      val regexp2str = """.*\.type#"""
      val regexp2 = (regexp2str + """(.*)""").r
      
      (str.replaceAll("""\n""", "")) match {
        case regexp1(clazz, typeArgs) => "%s[%s]".format(clazz, typeArgs.replaceAll(regexp2str, ""))
        case regexp2(clazz)           => clazz
        case _                        => str
      }
    }
  }
  def breakIf(assertion: => Boolean, args: DebugParam[_]*): Unit =
    if (assertion) break(args.toList)

  // start a repl, binding supplied args
  def break(args: List[DebugParam[_]]): Unit = {
    val intLoop = new InterpreterLoop
    intLoop.settings = new Settings(Console.println)
    intLoop.createInterpreter
    intLoop.in = InteractiveReader.createDefault(intLoop.interpreter)
    
    // rebind exit so people don't accidentally call System.exit by way of predef
    intLoop.interpreter.beQuietDuring {
      intLoop.interpreter.interpret("""def exit = println("Type :quit to resume program execution.")""")
      for (p <- args) {
        intLoop.interpreter.bind(p.name, p.typeStr, p.param)
        println("%s: %s".format(p.name, p.typeStr))
      }
    }
    intLoop.repl()
    intLoop.closeInterpreter
  }
  
  def codegenln(leadingPlus: Boolean, xs: String*): String = codegen(leadingPlus, (xs ++ Array("\n")): _*)
  def codegenln(xs: String*): String = codegenln(true, xs: _*)
  def codegen(xs: String*): String = codegen(true, xs: _*)
  def codegen(leadingPlus: Boolean, xs: String*): String = {
    val front = if (leadingPlus) "+ " else ""
    xs.map("\"" + string2code(_) + "\"").mkString(front, " + ", "")
  }

  /** Convert a string into code that can recreate the string.
   *  This requires replacing all special characters by escape
   *  codes. It does not add the surrounding " marks.  */
  def string2code(str: String): String = {
    /** Convert a character to a backslash-u escape */
    def char2uescape(c: Char): String = {
      var rest = c.toInt
      val buf = new StringBuilder
      for (i <- 1 to 4) {
        buf ++= (rest % 16).toHexString
        rest = rest / 16
      }
      "\\u" + buf.toString.reverse
    }
    
    val res = new StringBuilder
    for (c <- str) c match {
      case '"' | '\'' | '\\'  => res += '\\' ; res += c
      case _ if c.isControl   => res ++= char2uescape(c)
      case _                  => res += c
    }
    res.toString
  }
}
