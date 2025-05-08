package mill.api

import java.net.URLClassLoader

/**
 * Convenience wrapper around `java.net.URLClassLoader`. Allows configuration
 * of shared class prefixes, and tracks creation/closing to help detect leaks
 */
class MillURLClassLoader(
    classPath: Iterable[java.nio.file.Path],
    parent: ClassLoader,
    sharedLoader: ClassLoader,
    sharedPrefixes: Iterable[String],
    val label: String
) extends URLClassLoader(
      classPath.iterator.map(_.toUri.toURL).toArray,
      MillURLClassLoader.refinePlatformParent(parent)
    ) {
  import MillURLClassLoader.*
  addOpenClassloader(label)
  override def findClass(name: String): Class[?] =
    if (sharedPrefixes.exists(name.startsWith)) sharedLoader.loadClass(name)
    else super.findClass(name)

  override def close() = {
    removeOpenClassloader(label)
    super.close()
  }

  // Random ID of the URLClassLoader to ensure it doesn't
  // duplicate (unlike System.identityHashCode), allowing tests to compare
  // hashcodes to verify whether the classloader has been re-created
  val identity: Int = scala.util.Random.nextInt()
}

object MillURLClassLoader {
  def countOpenClassloaders = countOpenClassloaders0.values.sum
  def countOpenClassloaders0 = openClassloaders.synchronized { openClassloaders.toMap }
  private[mill] val openClassloaders = collection.mutable.Map.empty[String, Int]

  private[mill] def addOpenClassloader(label: String) =
    openClassloaders.synchronized {
      // println(s"addOpenClassLoader ${classPath.hashCode}\n  " + new Exception().getStackTrace.mkString("\n  "))

      openClassloaders.updateWith(label) {
        case None => Some(1)
        case Some(n) => Some(n + 1)
      }
    }

  private[mill] def removeOpenClassloader(label: String) =
    openClassloaders.synchronized {
      // println(s"removeOpenClassLoader ${classPath.hashCode}\n  " + new Exception().getStackTrace.mkString("\n  "))
      openClassloaders.updateWith(label) {
        case Some(1) => None
        case Some(n) => Some(n - 1)
      }
    }

  /**
   * Return `ClassLoader.getPlatformClassLoader` for java 9 and above, if parent class loader is null,
   * otherwise return same parent class loader.
   * More details: https://docs.oracle.com/javase/9/migrate/toc.htm#JSMIG-GUID-A868D0B9-026F-4D46-B979-901834343F9E
   *
   * `ClassLoader.getPlatformClassLoader` call is implemented via runtime reflection, cause otherwise
   * mill could be compiled only with jdk 9 or above. We don't want to introduce this restriction now.
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

  private val java9OrAbove: Boolean =
    !System.getProperty("java.specification.version").startsWith("1.")
}
