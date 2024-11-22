package mill.api

import java.net.{URL, URLClassLoader}

import java.nio.file.{FileAlreadyExistsException, FileSystemException}

import mill.java9rtexport.Export
import scala.util.{Properties, Try}

/**
 * Utilities for creating classloaders for running compiled Java/Scala code in
 * isolated classpaths.
 */
object ClassLoader {

  def java9OrAbove: Boolean = !System.getProperty("java.specification.version").startsWith("1.")

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
      override def findClass(name: String): Class[?] = {
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
    if (parent != null) parent
    else if (java9OrAbove) {
      // Make sure when `parent == null`, we only delegate java.* classes
      // to the parent getPlatformClassLoader. This is necessary because
      // in Java 9+, somehow the getPlatformClassLoader ends up with all
      // sorts of other non-java stuff on it's classpath, which is not what
      // we want for an "isolated" classloader!
      classOf[ClassLoader]
        .getMethod("getPlatformClassLoader")
        .invoke(null)
        .asInstanceOf[ClassLoader]
    } else {
      // With Java 8 we want a clean classloader that still contains classes
      // coming from com.sun.* etc.
      // We get the application classloader parent which happens to be of
      // type sun.misc.Launcher$ExtClassLoader
      // We can't call the method directly since it would not compile on Java 9+
      // So we load it via reflection to allow compilation in Java 9+ but only
      // on Java 8
      val launcherClass = getClass.getClassLoader().loadClass("sun.misc.Launcher")
      val getLauncherMethod = launcherClass.getMethod("getLauncher")
      val launcher = getLauncherMethod.invoke(null)
      val getClassLoaderMethod = launcher.getClass().getMethod("getClassLoader")
      val appClassLoader = getClassLoaderMethod.invoke(launcher).asInstanceOf[ClassLoader]
      appClassLoader.getParent()
    }
  }

  private def makeUrls(urls: Seq[URL])(implicit ctx: Ctx.Home): Seq[URL] = {
    if (java9OrAbove) {
      val java90rtJar = ctx.home / Export.rtJarName
      if (!os.exists(java90rtJar)) {
        // Time between retries should go from 100 ms to around 10s, which should
        // leave plenty of time for another process to write fully this 50+ MB file
        val retry = Retry(
          count = 7,
          backoffMillis = 100,
          filter = {
            case (_, _: FileSystemException) if Properties.isWin => true
            case _ => false
          }
        )
        retry {
          try os.copy(os.Path(Export.rt()), java90rtJar, createFolders = true)
          catch { case e: FileAlreadyExistsException => /* someone else already did this */}
        }
      }
      urls :+ java90rtJar.toIO.toURI().toURL()
    } else {
      urls
    }
  }
}
