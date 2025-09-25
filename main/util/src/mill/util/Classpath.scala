package mill.util

import mill.api.BuildCtx

import java.io.File
import java.net.URL
import scala.collection.mutable
import scala.util.matching.Regex

/**
 * Loads the jars that make up the classpath of the scala-js-fiddle
 * compiler and re-shapes it into the correct structure to satisfy
 * scala-compile and scalajs-tools
 */
object Classpath {
  val traceClasspathIssues: Boolean =
    sys.props
      .get("ammonite.trace-classpath")
      .exists(_.toLowerCase == "true")

  /**
   * In memory cache of all the jars used in the compiler. This takes up some
   * memory but is better than reaching all over the filesystem every time we
   * want to do something.
   */
  def classpath(classLoader: ClassLoader): Vector[os.Path] = {

    var current = classLoader
    val files = collection.mutable.Buffer.empty[os.Path]
    val seenClassLoaders = collection.mutable.Buffer.empty[ClassLoader]
    while (current != null) {
      seenClassLoaders.append(current)
      current match {
        case t: java.net.URLClassLoader =>
          files.appendAll(
            t.getURLs
              .collect {
                case url if url.getProtocol == "file" => os.Path(java.nio.file.Paths.get(url.toURI))
              }
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
        for (p <- System.getProperty("java.class.path").split(File.pathSeparatorChar)) {
          val f = os.Path(p, BuildCtx.workspaceRoot)
          if (os.exists(f)) files.append(f)
        }
      }
    }
    files.toVector
  }

  val simpleNameRegex: Regex = "[a-zA-Z0-9_]+".r

  def allJars(classloader: ClassLoader): Seq[URL] = {
    allClassloaders(classloader)
      .collect { case t: java.net.URLClassLoader => t.getURLs }
      .flatten
      .toSeq
  }

  def allClassloaders(classloader: ClassLoader): mutable.Buffer[ClassLoader] = {
    val all = mutable.Buffer.empty[ClassLoader]
    var current = classloader
    while (current != null && current != ClassLoader.getSystemClassLoader) {
      all.append(current)
      current = current.getParent
    }
    all
  }
}
