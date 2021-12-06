package mill.api

import java.net.{URL, URLClassLoader}

import java.nio.file.FileAlreadyExistsException

import io.github.retronym.java9rtexport.Export
import scala.util.Try

object ClassLoader {

  def java9OrAbove = !System.getProperty("java.specification.version").startsWith("1.")

  @deprecated("Use other create method instead.", "mill after 0.9.7")
  def create(
      urls: Seq[URL],
      parent: java.lang.ClassLoader,
      sharedLoader: java.lang.ClassLoader,
      sharedPrefixes: Seq[String]
  )(implicit ctx: Ctx.Home): URLClassLoader = {
    create(
      urls = urls,
      parent = parent,
      sharedLoader = sharedLoader,
      sharedPrefixes = sharedPrefixes,
      logger = None
    )
  }

  def create(
      urls: Seq[URL],
      parent: java.lang.ClassLoader,
      sharedLoader: java.lang.ClassLoader = getClass.getClassLoader,
      sharedPrefixes: Seq[String] = Seq(),
      logger: Option[mill.api.Logger] = None
  )(implicit ctx: Ctx.Home): URLClassLoader = {
    new URLClassLoader(
      makeUrls(urls).toArray,
      refinePlatformParent(parent)
    ) {
      override def findClass(name: String): Class[_] = {
        if (sharedPrefixes.exists(name.startsWith)) {
          logger.foreach(
            _.debug(s"About to load class [${name}] from shared classloader [${sharedLoader}]")
          )
          sharedLoader.loadClass(name)
        } else super.findClass(name)
      }
    }
  }

  /**
   *  Return `ClassLoader.getPlatformClassLoader` for java 9 and above, if parent class loader is null,
   *  otherwise return same parent class loader.
   *  More details: https://docs.oracle.com/javase/9/migrate/toc.htm#JSMIG-GUID-A868D0B9-026F-4D46-B979-901834343F9E
   *
   *  `ClassLoader.getPlatformClassLoader` call is implemented via runtime reflection, cause otherwise
   *  mill could be compiled only with jdk 9 or above. We don't want to introduce this restriction now.
   */
  private def refinePlatformParent(parent: java.lang.ClassLoader): ClassLoader = {
    if (!java9OrAbove || parent != null) parent
    else {
      // Make sure when `parent == null`, we only delegate java.* classes
      // to the parent getPlatformClassLoader. This is necessary because
      // in Java 9+, somehow the getPlatformClassLoader ends up with all
      // sorts of other non-java stuff on it's classpath, which is not what
      // we want for an "isolated" classloader!
      classOf[ClassLoader]
        .getMethod("getPlatformClassLoader")
        .invoke(null)
        .asInstanceOf[ClassLoader]
    }
  }

  private def makeUrls(urls: Seq[URL])(implicit ctx: Ctx.Home): Seq[URL] = {
    if (java9OrAbove) {
      val java90rtJar = ctx.home / Export.rtJarName
      if (!os.exists(java90rtJar)) {
        Try {
          os.copy(os.Path(Export.rt()), java90rtJar, createFolders = true)
        }.recoverWith { case e: FileAlreadyExistsException =>
          // some race?
          if (os.exists(java90rtJar) && PathRef(java90rtJar) == PathRef(os.Path(Export.rt()))) Try {
            // all good
            ()
          }
          else Try {
            // retry
            os.copy(os.Path(Export.rt()), java90rtJar, replaceExisting = true, createFolders = true)
          }
        }.get
      }
      urls :+ java90rtJar.toIO.toURI().toURL()
    } else {
      urls
    }
  }
}
