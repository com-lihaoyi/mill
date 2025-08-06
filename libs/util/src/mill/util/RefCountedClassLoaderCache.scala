package mill.util

import mill.api.PathRef

import java.net.URLClassLoader
import scala.collection.mutable.LinkedHashMap

/**
 * Caches classloaders that can be shared between different workers, keeping
 * a reference count of each classloader and only closing it after no more
 * references exist
 */
class RefCountedClassLoaderCache(
    sharedLoader: ClassLoader = null,
    sharedPrefixes: Seq[String] = Nil,
    parent: ClassLoader = null
) {

  private val cache = LinkedHashMap.empty[Long, (URLClassLoader, Int)]

  def extraRelease(cl: ClassLoader): Unit = ()

  def release(combinedCompilerJars: Seq[PathRef]) = synchronized {
    val compilersSig = combinedCompilerJars.hashCode()
    cache.updateWith(compilersSig) {
      case Some((cl, 1)) =>
        // We try to find the timer created by scala.tools.nsc.classpath.FileBasedCache
        // and cancel it, so that it shuts down its thread.
        extraRelease(cl)
        cl.close()
        None
      case Some((cl, n)) if n > 1 => Some((cl, n - 1))
      case v => sys.error("Unknown: " + v) // No other cases; n should never be zero or negative
    }

  }
  def get(
      combinedCompilerJars: Seq[PathRef]
  )(implicit e: sourcecode.Enclosing): URLClassLoader = synchronized {
    val compilersSig = combinedCompilerJars.hashCode()
    cache.get(compilersSig) match {
      case Some((cl, i)) =>
        cache(compilersSig) = (cl, i + 1)
        cl
      case _ =>
        // the Scala compiler must load the `xsbti.*` classes from the same loader as `JvmWorkerImpl`
        val cl = mill.util.Jvm.createClassLoader(
          combinedCompilerJars.map(_.path),
          parent = parent,
          sharedLoader = sharedLoader,
          sharedPrefixes = sharedPrefixes
        )(e)
        cache.update(compilersSig, (cl, 1))
        cl
    }
  }
  class Foo

  def close() = {
    new Foo()
    cache.values.map { case (cl, _) => cl.close() }
    cache.clear()
  }

}
