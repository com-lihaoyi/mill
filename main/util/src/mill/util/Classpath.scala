package mill.util


import java.io.File
import java.net.URL
import java.nio.file.{Path, Paths}
import java.util.zip.{ZipFile, ZipInputStream}
import io.github.retronym.java9rtexport.Export

import scala.collection.mutable
import scala.util.control.NonFatal

/**
 * Loads the jars that make up the classpath of the scala-js-fiddle
 * compiler and re-shapes it into the correct structure to satisfy
 * scala-compile and scalajs-tools
 */
object Classpath {
  val traceClasspathIssues =
    sys.props
      .get("ammonite.trace-classpath")
      .exists(_.toLowerCase == "true")

  /**
   * In memory cache of all the jars used in the compiler. This takes up some
   * memory but is better than reaching all over the filesystem every time we
   * want to do something.
   */
  def classpath(classLoader: ClassLoader,
                rtCacheDir: os.Path): Vector[os.Path] = {

    var current = classLoader
    val files = collection.mutable.Buffer.empty[os.Path]
    val seenClassLoaders = collection.mutable.Buffer.empty[ClassLoader]
    while(current != null){
      seenClassLoaders.append(current)
      current match{
        case t: java.net.URLClassLoader =>
          files.appendAll(
            t.getURLs
              .map{url => (url.getProtocol, url.getPath)}
              .collect{case ("file", p) => os.Path(p)}
          )
        case _ =>
      }
      current = current.getParent
    }

    val sunBoot = System.getProperty("sun.boot.class.path")
    if (sunBoot != null) {
      files.appendAll(
        sunBoot
          .split(java.io.File.pathSeparator)
          .map(os.Path(_))
          .filter(os.exists(_))
      )
    } else {
      if (seenClassLoaders.contains(ClassLoader.getSystemClassLoader)) {
        for (p <- System.getProperty("java.class.path")
          .split(File.pathSeparatorChar) if !p.endsWith("sbt-launch.jar")) {
          val f = os.Path(p, os.pwd)
          if (os.exists(f)) files.append(f)
        }
        try {
          new java.net.URLClassLoader(files.map(_.toIO.toURI.toURL).toArray, null)
            .loadClass("javax.script.ScriptEngineManager")
        } catch {
          case _: ClassNotFoundException =>
            files.append(os.Path(Export.rtAt(rtCacheDir.toIO)))
        }
      }
    }
    files.toVector
  }

  val simpleNameRegex = "[a-zA-Z0-9_]+".r

  def allJars(classloader: ClassLoader): Seq[URL] = {
    allClassloaders(classloader)
      .collect { case t: java.net.URLClassLoader => t.getURLs }
      .flatten
      .toSeq
  }

  def allClassloaders(classloader: ClassLoader) = {
    val all = mutable.Buffer.empty[ClassLoader]
    var current = classloader
    while (current != null && current != ClassLoader.getSystemClassLoader) {
      all.append(current)
      current = current.getParent
    }
    all
  }

  /**
   * Stats all jars on the classpath, and loose class-files in the current
   * classpath that could conceivably be part of some package, and aggregates
   * their names and mtimes as a "signature" of the current classpath
   *
   * When looking for loose class files, we skip folders whose names are not
   * valid java identifiers. Otherwise, the "current classpath" often contains
   * the current directory, which in an SBT or Maven project contains hundreds
   * or thousands of files which are not on the classpath. Empirically, this
   * heuristic improves perf by greatly cutting down on the amount of files we
   * need to mtime in many common cases.
   */
  def initialClasspathSignature(classloader: ClassLoader): Seq[(Either[String, java.net.URL], Long)] = {


    def findMtimes(d: java.nio.file.Path): Seq[(Either[String, java.net.URL], Long)] = {
      def skipSuspicious(path: os.Path) = {
        // Leave out sketchy files which don't look like package names or
        // class files
        (simpleNameRegex.findPrefixOf(path.last) != Some(path.last)) &&
          !path.last.endsWith(".class")
      }

      os.walk(os.Path(d), skip = skipSuspicious).map(x => (Right(x), os.mtime(x))).map {
        case (e, lm) =>
          (e.right.map(_.toNIO.toUri.toURL), lm)
      }
    }


    val classpathRoots =
      allClassloaders(classloader)
        .collect { case cl: java.net.URLClassLoader => cl.getURLs }
        .flatten

    val bootClasspathRoots = sys.props("java.class.path")
      .split(java.io.File.pathSeparator)
      .map(java.nio.file.Paths.get(_).toAbsolutePath.toUri.toURL)

    val mtimes = (bootClasspathRoots ++ classpathRoots).flatMap { p =>
      if (p.getProtocol == "file") {
        val f = java.nio.file.Paths.get(p.toURI)
        if (!java.nio.file.Files.exists(f)) Nil
        else if (java.nio.file.Files.isDirectory(f)) findMtimes(f)
        else Seq(Right(p) -> os.mtime(os.Path(f)))
      } else
        Classpath.urlLastModified(p).toSeq.map((Right(p), _))
    }

    mtimes
  }

  def urlLastModified(url: URL): Option[Long] = {
    if (url.getProtocol == "file") {
      val path = os.Path(java.nio.file.Paths.get(url.toURI()).toFile(), os.root)
      if (os.exists(path)) Some(os.mtime(path)) else None
    } else {
      var c: java.net.URLConnection = null
      try {
        c = url.openConnection()
        Some(c.getLastModified)
      } catch {
        case e: java.io.FileNotFoundException =>
          None
      } finally {
        if (c != null)
          scala.util.Try(c.getInputStream.close())
      }
    }
  }
  def canBeOpenedAsJar(url: URL): Boolean = {
    var zis: ZipInputStream = null
    try {
      if (url.getProtocol == "file") {
        // this ignores any preamble in particular, unlike ZipInputStream
        val zf = new ZipFile(new File(url.toURI))
        zf.close()
        true
      } else {
        zis = new ZipInputStream(url.openStream())
        zis.getNextEntry != null
      }
    } catch {
      case NonFatal(e) =>
        traceClasspathProblem(
          s"Classpath element '$url' "+
            s"could not be opened as jar file because of $e"
        )
        false
    } finally {
      if (zis != null)
        zis.close()
    }
  }
  def traceClasspathProblem(msg: String): Unit =
    if (traceClasspathIssues) println(msg)
}
