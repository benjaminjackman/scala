/* NSC -- new Scala compiler
 * Copyright 2006-2010 LAMP/EPFL
 * @author  Martin Odersky
 */

// $Id$

package scala.tools.nsc
package util

import java.io.File
import java.net.URL
import java.util.StringTokenizer
import scala.util.Sorting

import scala.collection.mutable.{ListBuffer, ArrayBuffer, HashSet => MutHashSet}
import scala.tools.nsc.io.AbstractFile

import ch.epfl.lamp.compiler.msil.{Type => MSILType, Assembly}


/** <p>
 *    This module provides star expansion of '-classpath' option arguments, behaves the same as
 *    java, see [http://java.sun.com/javase/6/docs/technotes/tools/windows/classpath.html]
 *  </p>
 *
 *  @author Stepan Koltsov
 */
object ClassPath {
  /** Expand single path entry */
  private def expandS(pattern: String): List[String] = {
    def isJar(name: String) = name.toLowerCase endsWith ".jar"

    /** Get all jars in directory */
    def lsJars(f: File, filt: String => Boolean = _ => true) = {
      val list = f.listFiles()
      if (list eq null) Nil
      else list.filter(f => f.isFile() && filt(f.getName) && isJar(f.getName())).map(_.getPath()).toList
    }

    val suffix = File.separator + "*"

    def basedir(s: String) = 
      if (s contains File.separator) s.substring(0, s.lastIndexOf(File.separator))
      else "."
    
    if (pattern == "*") lsJars(new File("."))
    else if (pattern endsWith suffix) lsJars(new File(pattern dropRight 2))
    else if (pattern contains '*') {
      val regexp = ("^%s$" format pattern.replaceAll("""\*""", """.*""")).r
      lsJars(new File(basedir(pattern)), regexp findFirstIn _ isDefined)
    }
    else List(pattern)
  }

  /** Split path using platform-dependent path separator */
  private def splitPath(path: String): List[String] =
    path split File.pathSeparator toList

  /** Expand path and possibly expanding stars */
  def expandPath(path: String, expandStar: Boolean = true): List[String] =
    if (expandStar) splitPath(path).flatMap(expandS(_))
    else splitPath(path)

  var XO = false

  def collectTypes(assemFile: AbstractFile) = {
    var res: Array[MSILType] = MSILType.EmptyTypes
    val assem = Assembly.LoadFrom(assemFile.path)
    if (assem != null) {
      // DeclaringType == null: true for non-inner classes
      res = assem.GetTypes().filter((typ: MSILType) => typ.DeclaringType == null)
      Sorting.stableSort(res, (t1: MSILType, t2: MSILType) => (t1.FullName compareTo t2.FullName) < 0)
    }
    res
  }
}

/**
 * Represents classes which can be loaded with a ClassfileLoader/MSILTypeLoader
 * and / or a SourcefileLoader.
 */
case class ClassRep[T](binary: Option[T], source: Option[AbstractFile]) {
  def name = {
    if (binary.isDefined) binary.get match {
      case f: AbstractFile =>
        assert(f.name.endsWith(".class"), f.name)
        f.name dropRight 6
      case t: MSILType =>
        t.Name
      case c =>
        throw new FatalError("Unexpected binary class representation: "+ c)
    } else {
      assert(source.isDefined)
      val nme = source.get.name
      if (nme.endsWith(".scala"))
        nme dropRight 6
      else if (nme.endsWith(".java"))
        nme dropRight 5
      else
        throw new FatalError("Unexpected source file ending: " + nme)
    }
  }
}

/**
 * Represents a package which contains classes and other packages
 */
abstract class ClassPath[T] {
  /**
   * The short name of the package (without prefix)
   */
  def name: String
  val classes: List[ClassRep[T]]
  val packages: List[ClassPath[T]]
  val sourcepaths: List[AbstractFile]
  
  /** Whether this classpath is being used for an optimized build.
   *  Why this is necessary is something which should really be documented,
   *  since it seems to have little to do with a ClassPath.
   */
  def isOptimized: Boolean
  
  /** Filters for assessing validity of various entities.
   */
  def validClassFile(name: String) =
    (name endsWith ".class") && (isOptimized || !(name endsWith "$class.class"))
    
  def validPackage(name: String) =
    !(name.equals("META-INF") || name.startsWith("."))

  def validSourceFile(name: String) =
    (name.endsWith(".scala") || name.endsWith(".java"))

  /**
   * Find a ClassRep given a class name of the form "package.subpackage.ClassName".
   * Does not support nested classes on .NET
   */
  def findClass(name: String): Option[ClassRep[T]] = {
    val i = name.indexOf('.')
    if (i < 0) {
      classes.find(c => c.name == name)
    } else {
      val pkg = name take i
      val rest = name drop (i + 1)
      packages.find(p => p.name == pkg).flatMap(_.findClass(rest))
    }
  }
}

/**
 * A Classpath containing source files
 */
class SourcePath[T](dir: AbstractFile, val isOptimized: Boolean) extends ClassPath[T] {
  def name = dir.name

  lazy val classes = {
    val cls = new ListBuffer[ClassRep[T]]
    for (f <- dir.iterator) {
      if (!f.isDirectory && validSourceFile(f.name))
        cls += ClassRep[T](None, Some(f))
    }
    cls.toList
  }

  lazy val packages = {
    val pkg = new ListBuffer[SourcePath[T]]
    for (f <- dir.iterator) {
      if (f.isDirectory && validPackage(f.name))
        pkg += new SourcePath[T](f, isOptimized)
    }
    pkg.toList
  }

  val sourcepaths: List[AbstractFile] = List(dir)

  override def toString() = "sourcepath: "+ dir.toString()
}

/**
 * A directory (or a .jar file) containing classfiles and packages
 */
class DirectoryClassPath(dir: AbstractFile, val isOptimized: Boolean) extends ClassPath[AbstractFile] {
  def name = dir.name

  lazy val classes = {
    val cls = new ListBuffer[ClassRep[AbstractFile]]
    for (f <- dir.iterator) {
      if (!f.isDirectory && validClassFile(f.name))
        cls += ClassRep(Some(f), None)
    }
    cls.toList
  }

  lazy val packages = {
    val pkg = new ListBuffer[DirectoryClassPath]
    for (f <- dir.iterator) {
      if (f.isDirectory && validPackage(f.name))
        pkg += new DirectoryClassPath(f, isOptimized)
    }
    pkg.toList
  }

  val sourcepaths: List[AbstractFile] = Nil
  
  override def toString() = "directory classpath: "+ dir.toString()
}



/**
 * A assembly file (dll / exe) containing classes and namespaces
 */
class AssemblyClassPath(types: Array[MSILType], namespace: String, val isOptimized: Boolean) extends ClassPath[MSILType] {
  def name = {
    val i = namespace.lastIndexOf('.')
    if (i < 0) namespace
    else namespace drop (i + 1)
  }

  def this(assemFile: AbstractFile, isOptimized: Boolean) {
    this(ClassPath.collectTypes(assemFile), "", isOptimized)
  }

  private lazy val first: Int = {
    var m = 0
    var n = types.length - 1
    while (m < n) {
      val l = (m + n) / 2
      val res = types(l).FullName.compareTo(namespace)
      if (res < 0) m = l + 1
      else n = l
    }
    if (types(m).FullName.startsWith(namespace)) m else types.length
  }

  lazy val classes = {
    val cls = new ListBuffer[ClassRep[MSILType]]
    var i = first
    while (i < types.length && types(i).Namespace.startsWith(namespace)) {
      // CLRTypes used to exclude java.lang.Object and java.lang.String (no idea why..)
      if (types(i).Namespace == namespace)
        cls += ClassRep(Some(types(i)), None)
      i += 1
    }
    cls.toList
  }

  lazy val packages = {
    val nsSet = new MutHashSet[String]
    var i = first
    while (i < types.length && types(i).Namespace.startsWith(namespace)) {
      val subns = types(i).Namespace
      if (subns.length > namespace.length) {
        // example: namespace = "System", subns = "System.Reflection.Emit"
        //   => find second "." and "System.Reflection" to nsSet.
        val end = subns.indexOf('.', namespace.length + 1)
        nsSet += (if (end < 0) subns
                  else subns.substring(0, end))
      }
      i += 1
    }
    for (ns <- nsSet.toList)
      yield new AssemblyClassPath(types, ns, isOptimized)
  }

  val sourcepaths: List[AbstractFile] = Nil
  
  override def toString() = "assembly classpath "+ namespace
}

/**
 * A classpath unifying multiple class- and sourcepath entries.
 */
abstract class MergedClassPath[T] extends ClassPath[T] {
  outer =>
  
  protected val entries: List[ClassPath[T]]

  def name = entries.head.name

  lazy val classes: List[ClassRep[T]] = {
    val cls = new ListBuffer[ClassRep[T]]
    for (e <- entries; c <- e.classes) {
      val name = c.name
      val idx = cls.indexWhere(cl => cl.name == name)
      if (idx >= 0) {
        val existing = cls(idx)
        if (existing.binary.isEmpty && c.binary.isDefined)
          cls(idx) = existing.copy(binary = c.binary)
        if (existing.source.isEmpty && c.source.isDefined)
          cls(idx) = existing.copy(source = c.source)
      } else {
        cls += c
      }
    }
    cls.toList
  }

  lazy val packages: List[ClassPath[T]] = {
    val pkg = new ListBuffer[ClassPath[T]]
    for (e <- entries; p <- e.packages) {
      val name = p.name
      val idx = pkg.indexWhere(pk => pk.name == name)
      if (idx >= 0) {
        pkg(idx) = addPackage(pkg(idx), p)
      } else {
        pkg += p
      }
    }
    pkg.toList
  }

  lazy val sourcepaths: List[AbstractFile] = entries.flatMap(_.sourcepaths)

  private def addPackage(to: ClassPath[T], pkg: ClassPath[T]) = to match {
    case cp: MergedClassPath[_] =>
      newMergedClassPath(cp.entries ::: List(pkg))
    case _ =>
      newMergedClassPath(List(to, pkg))
  }

  private def newMergedClassPath(entrs: List[ClassPath[T]]): MergedClassPath[T] =
    new MergedClassPath[T] {
      protected val entries = entrs
      override def isOptimized = outer.isOptimized
    }
  
  override def toString() = "merged classpath "+ entries.mkString("(", "\n", ")")
}

/**
 * The classpath when compiling with target:jvm. Binary files (classfiles) are represented
 * as AbstractFile. nsc.io.ZipArchive is used to view zip/jar archives as directories.
 */
class JavaClassPath(boot: String, ext: String, user: String, source: String, Xcodebase: String, val isOptimized: Boolean)
extends MergedClassPath[AbstractFile] {

  protected val entries: List[ClassPath[AbstractFile]] = assembleEntries()
  private def assembleEntries(): List[ClassPath[AbstractFile]] = {
    import ClassPath._
    val etr = new ListBuffer[ClassPath[AbstractFile]]

    def addFilesInPath(path: String, expand: Boolean,
          ctr: AbstractFile => ClassPath[AbstractFile] = x => new DirectoryClassPath(x, isOptimized)) {
      for (fileName <- expandPath(path, expandStar = expand)) {
        val file = AbstractFile.getDirectory(fileName)
        if (file ne null) etr += ctr(file)
      }
    }

    // 1. Boot classpath
    addFilesInPath(boot, false)

    // 2. Ext classpath
    for (fileName <- expandPath(ext, expandStar = false)) {
      val dir = AbstractFile.getDirectory(fileName)
      if (dir ne null) {
        for (file <- dir) {
          val name = file.name.toLowerCase
          if (name.endsWith(".jar") || name.endsWith(".zip") || file.isDirectory) {
            val archive = AbstractFile.getDirectory(new File(dir.file, name))
            if (archive ne null) etr += new DirectoryClassPath(archive, isOptimized)
          }
        }
      }
    }

    // 3. User classpath
    addFilesInPath(user, true)

    // 4. Codebase entries (URLs)
    {
      val urlSeparator = " "
      val urlStrtok = new StringTokenizer(Xcodebase, urlSeparator)
      while (urlStrtok.hasMoreTokens()) try {
        val url = new URL(urlStrtok.nextToken())
        val archive = AbstractFile.getURL(url)
        if (archive ne null) etr += new DirectoryClassPath(archive, isOptimized)
      }
      catch {
        case e =>
          Console.println("error adding classpath form URL: " + e.getMessage)//debug
        throw e
      }
    }

    // 5. Source path
    if (source != "")
      addFilesInPath(source, false, x => new SourcePath[AbstractFile](x, isOptimized))

    etr.toList
  }
}

/**
 * The classpath when compiling with target:msil. Binary files are represented as
 * MSILType values.
 */
class MsilClassPath(ext: String, user: String, source: String, val isOptimized: Boolean) extends MergedClassPath[MSILType] {
  protected val entries: List[ClassPath[MSILType]] = assembleEntries()

  private def assembleEntries(): List[ClassPath[MSILType]] = {
    import ClassPath._
    val etr = new ListBuffer[ClassPath[MSILType]]
    val names = new MutHashSet[String]

    // 1. Assemblies from -Xassem-extdirs
    for (dirName <- expandPath(ext, expandStar = false)) {
      val dir = AbstractFile.getDirectory(dirName)
      if (dir ne null) {
        for (file <- dir) {
          val name = file.name.toLowerCase
          if (name.endsWith(".dll") || name.endsWith(".exe")) {
            names += name
            etr += new AssemblyClassPath(file, isOptimized)
          }
        }
      }
    }

    // 2. Assemblies from -Xassem-path
    for (fileName <- expandPath(user, expandStar = false)) {
      val file = AbstractFile.getFile(fileName)
      if (file ne null) {
        val name = file.name.toLowerCase
        if (name.endsWith(".dll") || name.endsWith(".exe")) {
          names += name
          etr += new AssemblyClassPath(file, isOptimized)
        }
      }
    }

    def check(n: String) {
      if (!names.contains(n))
      throw new AssertionError("Cannot find assembly "+ n +
         ". Use -Xassem-extdirs or -Xassem-path to specify its location")
    }
    check("mscorlib.dll")
    check("scalaruntime.dll")

    // 3. Source path
    for (dirName <- expandPath(source, expandStar = false)) {
      val file = AbstractFile.getDirectory(dirName)
      if (file ne null) etr += new SourcePath[MSILType](file, isOptimized)
    }

    etr.toList
  }
}
