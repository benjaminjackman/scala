/* NSC -- new Scala compiler
 * Copyright 2005-2010 LAMP/EPFL
 * @author Alexander Spoon
 */
// $Id$

package scala.tools.nsc

import java.io.{ BufferedReader, File, FileReader, PrintWriter }
import java.io.IOException

import scala.tools.nsc.{ InterpreterResults => IR }
import interpreter._
import io.{ Process }

// Classes to wrap up interpreter commands and their results
// You can add new commands by adding entries to val commands
// inside InterpreterLoop.
object InterpreterControl {
  // the default result means "keep running, and don't record that line"
  val defaultResult = Result(true, None)
  
  // a single interpreter command
  sealed abstract class Command extends Function1[List[String], Result] {
    def name: String
    def help: String
    def error(msg: String) = {
      println(":" + name + " " + msg + ".")
      Result(true, None)
    }
    def usage(): String
  }
  
  case class NoArgs(name: String, help: String, f: () => Result) extends Command {
    def usage(): String = ":" + name
    def apply(args: List[String]) = if (args.isEmpty) f() else error("accepts no arguments")
  }
  
  case class LineArg(name: String, help: String, f: (String) => Result) extends Command {
    def usage(): String = ":" + name + " <line>"
    def apply(args: List[String]) = f(args mkString " ")
  }

  case class OneArg(name: String, help: String, f: (String) => Result) extends Command {
    def usage(): String = ":" + name + " <arg>"
    def apply(args: List[String]) =
      if (args.size == 1) f(args.head)
      else error("requires exactly one argument")
  }

  case class VarArgs(name: String, help: String, f: (List[String]) => Result) extends Command {
    def usage(): String = ":" + name + " [arg]"
    def apply(args: List[String]) = f(args)
  }

  // the result of a single command
  case class Result(keepRunning: Boolean, lineToRecord: Option[String])
}
import InterpreterControl._

/** The 
 *  <a href="http://scala-lang.org/" target="_top">Scala</a>
 *  interactive shell.  It provides a read-eval-print loop around
 *  the Interpreter class.
 *  After instantiation, clients should call the <code>main()</code> method.
 *
 *  <p>If no in0 is specified, then input will come from the console, and
 *  the class will attempt to provide input editing feature such as
 *  input history.
 *
 *  @author Moez A. Abdel-Gawad
 *  @author  Lex Spoon
 *  @version 1.2
 */
class InterpreterLoop(in0: Option[BufferedReader], out: PrintWriter) {
  def this(in0: BufferedReader, out: PrintWriter) = this(Some(in0), out)
  def this() = this(None, new PrintWriter(Console.out))

  /** The input stream from which commands come, set by main() */
  var in: InteractiveReader = _
  def historyList = in.history map (_.asList) getOrElse Nil

  /** The context class loader at the time this object was created */
  protected val originalClassLoader = Thread.currentThread.getContextClassLoader
  
  var settings: Settings = _          // set by main()
  var interpreter: Interpreter = _    // set by createInterpreter()
  def isettings = interpreter.isettings
    
  // XXX
  var addedClasspath: List[String] = Nil

  /** A reverse list of commands to replay if the user requests a :replay */
  var replayCommandsRev: List[String] = Nil

  /** A list of commands to replay if the user requests a :replay */
  def replayCommands = replayCommandsRev.reverse

  /** Record a command for replay should the user request a :replay */
  def addReplay(cmd: String) = replayCommandsRev = cmd :: replayCommandsRev

  /** Close the interpreter and set the var to <code>null</code>. */
  def closeInterpreter() {
    if (interpreter ne null) {
      interpreter.close
      interpreter = null
      Thread.currentThread.setContextClassLoader(originalClassLoader)
    }
  }

  /** Create a new interpreter. */
  def createInterpreter() {
    if (!addedClasspath.isEmpty)
      settings.classpath.value += addedClasspath.map(File.pathSeparator + _).mkString
      
    interpreter = new Interpreter(settings, out) {
      override protected def parentClassLoader = classOf[InterpreterLoop].getClassLoader
    }
    interpreter.setContextClassLoader()
  }

  /** Bind the settings so that evaluated code can modify them */
  def bindSettings() {
    isettings
    // interpreter.beQuietDuring {
    //   interpreter.compileString(InterpreterSettings.sourceCodeForClass)
    //   interpreter.bind("settings", "scala.tools.nsc.InterpreterSettings", isettings)
    // }
  }

  /** print a friendly help message */
  def printHelp() = {
    out println "All commands can be abbreviated - for example :he instead of :help.\n"
    val cmds = commands map (x => (x.usage, x.help))
    val width: Int = cmds map { case (x, _) => x.length } max
    val formatStr = "%-" + width + "s %s"
    cmds foreach { case (usage, help) => out println formatStr.format(usage, help) }
  } 
  
  /** Print a welcome message */
  def printWelcome() {
    import Properties._
    val welcomeMsg = 
     """|Welcome to Scala %s (%s, Java %s).
        |Type in expressions to have them evaluated.
        |Type :help for more information.""" .
    stripMargin.format(versionString, javaVmName, javaVersion)
        
    out println welcomeMsg
    out.flush
  }
  
  /** Show the history */
  def printHistory(xs: List[String]) {
    val defaultLines = 20
    
    if (in.history.isEmpty)
      return println("No history available.")

    val current = in.history.get.index
    val count = try xs.head.toInt catch { case _: Exception => defaultLines }
    val lines = historyList takeRight count
    val offset = current - lines.size + 1

    for ((line, index) <- lines.zipWithIndex)
      println("%d %s".format(index + offset, line))
  }
  
  /** Search the history */
  def searchHistory(_cmdline: String) {
    val cmdline = _cmdline.toLowerCase
    
    if (in.history.isEmpty)
      return println("No history available.")
    
    val current = in.history.get.index
    val offset = current - historyList.size + 1
    
    for ((line, index) <- historyList.zipWithIndex ; if line.toLowerCase contains cmdline)
      println("%d %s".format(index + offset, line))
  }
  
  /** Prompt to print when awaiting input */
  val prompt = Properties.shellPromptString

  // most commands do not want to micromanage the Result, but they might want
  // to print something to the console, so we accomodate Unit and String returns.
  object CommandImplicits {
    implicit def u2ir(x: Unit): Result = defaultResult
    implicit def s2ir(s: String): Result = {
      out println s
      defaultResult
    }
  }
  
  /** Standard commands **/
  val standardCommands: List[Command] = {
    import CommandImplicits._
    List(
       NoArgs("help", "print this help message", printHelp),
       VarArgs("history", "show the history (optional arg: lines to show)", printHistory),
       LineArg("h?", "search the history", searchHistory),
       OneArg("jar", "add a jar to the classpath", addJar),
       OneArg("load", "load and interpret a Scala file", load),
       NoArgs("power", "enable power user mode", power),
       NoArgs("quit", "exit the interpreter", () => Result(false, None)),
       NoArgs("replay", "reset execution and replay all previous commands", replay),
       LineArg("sh", "fork a shell and run a command", runShellCmd),
       NoArgs("silent", "disable/enable automatic printing of results", verbosity) 
    )
  }
  
  /** Power user commands */
  // XXX - why does a third argument like "interpreter dumpState(_)" throw an NPE
  // while the version below works?
  var powerUserOn = false
  val powerCommands: List[Command] = {
    import CommandImplicits._
    List(
      VarArgs("dump", "displays a view of the interpreter's internal state", 
        (xs: List[String]) => interpreter dumpState xs),
      VarArgs("tree", "displays ASTs for specified identifiers",
        (xs: List[String]) => interpreter dumpTrees xs)
      // LineArg("meta", "given code which produces scala code, executes the results",
      //   (xs: List[String]) => )
    )
  }
  
  /** Available commands */
  def commands: List[Command] = standardCommands ::: (if (powerUserOn) powerCommands else Nil)

  /** The main read-eval-print loop for the interpreter.  It calls
   *  <code>command()</code> for each line of input, and stops when
   *  <code>command()</code> returns <code>false</code>.
   */
  def repl() {
    def readOneLine() = {
      out.flush
      in readLine prompt
    }
    // return false if repl should exit
    def processLine(line: String): Boolean =       
      if (line eq null) false               // assume null means EOF
      else command(line) match {
        case Result(false, _)           => false
        case Result(_, Some(finalLine)) => addReplay(finalLine) ; true
        case _                          => true
      }
    
    /* For some reason, the first interpreted command always takes
     * a second or two.  So, wait until the welcome message
     * has been printed before calling bindSettings.  That way,
     * the user can read the welcome message while this
     * command executes.
     */
    val futLine = scala.concurrent.ops.future(readOneLine)
    bindSettings()
    if (!processLine(futLine()))
      return

    // loops until false, then returns
    while (processLine(readOneLine)) { }
  }

  /** interpret all lines from a specified file */
  def interpretAllFrom(filename: String) {
    val fileIn = 
      try   { new FileReader(filename) }
      catch { case _:IOException => return out.println("Error opening file: " + filename) }
      
    val oldIn = in
    val oldReplay = replayCommandsRev
    try {
      val inFile = new BufferedReader(fileIn)
      in = new SimpleReader(inFile, out, false)
      out.println("Loading " + filename + "...")
      out.flush
      repl
    } finally {
      in = oldIn
      replayCommandsRev = oldReplay
      fileIn.close
    }
  }

  /** create a new interpreter and replay all commands so far */
  def replay() {
    closeInterpreter()
    createInterpreter()
    for (cmd <- replayCommands) {
      out.println("Replaying: " + cmd)
      out.flush()  // because maybe cmd will have its own output
      command(cmd)
      out.println
    }
  }
  
  /** fork a shell and run a command */
  def runShellCmd(line: String) {
    // we assume if they're using :sh they'd appreciate being able to pipeline
    interpreter.beQuietDuring {
      interpreter.interpret("import _root_.scala.tools.nsc.io.Process.Pipe._")
    }
    val p = Process(line)
    // only bind non-empty streams
    def add(name: String, it: Iterator[String]) =
      if (it.hasNext) interpreter.bind(name, "scala.List[String]", it.toList)
    
    List(("stdout", p.stdout), ("stderr", p.stderr)) foreach (add _).tupled
  }
  
  def withFile(filename: String)(action: String => Unit) {
    if (! new File(filename).exists) out.println("That file does not exist")
    else action(filename)
  }
  
  def load(arg: String) = {
    var shouldReplay: Option[String] = None
    withFile(arg)(f => {
      interpretAllFrom(f)
      shouldReplay = Some(":load " + arg)
    })
    Result(true, shouldReplay)
  }
  

  def addJar(arg: String): Unit = {
    val f = new java.io.File(arg)
    if (!f.exists) {
      out.println("The file '" + f + "' doesn't seem to exist.")
      return
    }
    addedClasspath = addedClasspath ::: List(f.getCanonicalPath)
    println("Added " + f.getCanonicalPath + " to your classpath.")
    replay()
  }
  
  def power() {
    powerUserOn = true
    out println interpreter.powerUser()
    if (in.history.isDefined)
      interpreter.quietBind("history", "scala.collection.immutable.List[String]", historyList)

    interpreter.quietBind("repl", "scala.tools.nsc.Interpreter#ReplVars", interpreter.replVarsObject())
  }
  
  def verbosity() = {
    val old = interpreter.printResults
    interpreter.printResults = !old
    out.println("Switched " + (if (old) "off" else "on") + " result printing.")
  }
  
  /** Run one command submitted by the user.  Two values are returned:
    * (1) whether to keep running, (2) the line to record for replay,
    * if any. */
  def command(line: String): Result = {
    def withError(msg: String) = {
      out println msg
      Result(true, None)
    }
    def ambiguous(cmds: List[Command]) = "Ambiguous: did you mean " + cmds.map(":" + _.name).mkString(" or ") + "?"

    // not a command
    if (!line.startsWith(":"))
      return Result(true, interpretStartingWith(line))

    val tokens = line.substring(1).split("""\s+""").toList
    if (tokens.isEmpty)
      return withError(ambiguous(commands))
    
    val (cmd :: args) = tokens
    
    // this lets us add commands willy-nilly and only requires enough command to disambiguate
    commands.filter(_.name startsWith cmd) match {
      case List(x)  => x(args)
      case Nil      => withError("Unknown command.  Type :help for help.")
      case xs       => withError(ambiguous(xs))
    }
  }
  
  /** Interpret expressions starting with the first line.
    * Read lines until a complete compilation unit is available
    * or until a syntax error has been seen.  If a full unit is
    * read, go ahead and interpret it.  Return the full string
    * to be recorded for replay, if any.
    */
  def interpretStartingWith(code: String): Option[String] =
    if (code startsWith ".") interpretStartingWith(interpreter.mostRecentVar + code)
    else interpreter.interpret(code) match {
      case IR.Error       => None
      case IR.Success     => Some(code)
      case IR.Incomplete  =>
        if (in.interactive && code.endsWith("\n\n")) {
          out.println("You typed two blank lines.  Starting a new command.")
          None
        } 
        else in.readLine("     | ") match {
          case null => None         // end of file
          case line => interpretStartingWith(code + "\n" + line)
        }
    }

  // runs :load <file> on any files passed via -i
  def loadFiles(settings: Settings) = settings match {
    case settings: GenericRunnerSettings =>
      for (filename <- settings.loadfiles.value) {
        val cmd = ":load " + filename
        command(cmd)
        replayCommandsRev = cmd :: replayCommandsRev
        out.println()
      }
    case _ =>
  }

  def main(settings: Settings) {
    this.settings = settings
    createInterpreter()
    
    // sets in to some kind of reader depending on environmental cues
    in = in0 match {
      case Some(in0)  => new SimpleReader(in0, out, true)
      case None       =>
        val emacsShell = System.getProperty("env.emacs", "") != ""
        
        // the interpeter is passed as an argument to expose tab completion info
        if (settings.Xnojline.value || emacsShell) new SimpleReader
        else if (settings.noCompletion.value) InteractiveReader.createDefault()
        else InteractiveReader.createDefault(interpreter, this)
    }

    loadFiles(settings)
    try {
      // it is broken on startup; go ahead and exit
      if (interpreter.reporter.hasErrors) return
      
      printWelcome()
      repl()
    } finally {
      closeInterpreter()
    }
  }

  // injects one value into the repl; returns pair of name and class
  def injectOne(name: String, obj: Any): Tuple2[String, String] = {
    val className = obj.asInstanceOf[AnyRef].getClass.getName     
    interpreter.quietBind(name, className, obj)
    (name, className)
  }
  
  // injects list of values into the repl; returns summary string
  def inject(args: List[Any]): String = {
    val strs = 
      for ((arg, i) <- args.zipWithIndex) yield {
        val varName = "p" + (i + 1)
        val (vname, vtype) = injectOne(varName, arg)
        vname + ": " + vtype
      }
    
    if (strs.size == 0) "Set no variables."
    else "Variables set:\n" + strs.mkString("\n")
  }
  
  /** process command-line arguments and do as they request */
  def main(args: Array[String]) {
    def error1(msg: String) = out println ("scala: " + msg)
    val command = new InterpreterCommand(args.toList, error1)
    def neededHelp(): String =
      (if (command.settings.help.value) command.usageMsg + "\n" else "") +
      (if (command.settings.Xhelp.value) command.xusageMsg + "\n" else "")
    
    // if they asked for no help and command is valid, we call the real main
    neededHelp() match {
      case ""     => if (command.ok) main(command.settings) // else nothing
      case help   => out print help ; out flush
    }
  }
}
