package mill.api

import java.net.{URL, URLClassLoader}

/**
 * Utilities for creating classloaders for running compiled Java/Scala code in
 * isolated classpaths.
 */
object ClassLoader {

  def withContextClassLoader[T](cl: java.lang.ClassLoader)(t: => T): T = {
    val thread = Thread.currentThread()
    val oldCl = thread.getContextClassLoader()
    try {
      thread.setContextClassLoader(cl)
      t
    } finally thread.setContextClassLoader(oldCl)
  }
  def java9OrAbove: Boolean = !System.getProperty("java.specification.version").startsWith("1.")

  def create(
      urls: Seq[URL],
      parent: java.lang.ClassLoader,
      sharedLoader: java.lang.ClassLoader = getClass.getClassLoader,
      sharedPrefixes: Seq[String] = Seq(),
      logger: Option[mill.api.Logger] = None
  )(implicit ctx: Ctx.Home): URLClassLoader = {
    new URLClassLoader(urls.toArray, refinePlatformParent(parent)) {
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
}
