package mill.api.daemon

import java.net.URLClassLoader
import scala.annotation.static

/**
 * Convenience wrapper around `java.net.URLClassLoader`. Allows configuration
 * of shared class prefixes, and tracks creation/closing to help detect leaks.
 *
 * This classloader is parallel capable.
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

  require(initialized)

  addOpenClassloader(label)

  override def findClass(name: String): Class[?] =
    if (sharedPrefixes.exists(name.startsWith))
      try {
        sharedLoader.loadClass(name)
      } catch {
        case _: ClassNotFoundException =>
          // this fixes the case, when different projects share the same class namespace
          // like `scala.meta` which is not part of scala-library.
          // See issue https://github.com/com-lihaoyi/mill/issues/5759 (Scalameta in task does not work)
          // If this turns our to be too permissive, we could also track
          // the packages we successfully loaded from the shared classloader,
          // and don't allow classes in the same package (split-packages) to come from different classloaders.
          super.findClass(name)
      }
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

  @static private val initialized: Boolean = {
    // Unused static field here exists for its side-effect:
    // we statically initialize the classloader as parallel capable
    java.lang.ClassLoader.registerAsParallelCapable()
  }

  /**
   * Shows a count of what `MillURLClassLoader`s are instantiated and have not yet
   * been closed. Useful for debugging classloader memory leaks
   */
  def countOpenClassloaders = openClassloaders.synchronized { openClassloaders.toMap }
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
        case None => throw Exception(s"removeOpenClassLoader $label not found")
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
