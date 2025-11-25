package mill.util

import mill.api.PathRef

import java.net.URLClassLoader

/**
 * Caches classloaders that can be shared between different workers, keeping
 * a reference count of each classloader and only closing it after no more
 * references exist
 */
class RefCountedClassLoaderCache(
    sharedLoader: ClassLoader = null,
    sharedPrefixes: Seq[String] = Nil,
    parent: ClassLoader = null
) extends AutoCloseable {

  private val cache = RefCountedCache[Seq[PathRef], Long, sourcecode.Enclosing, URLClassLoader](
    convertKey = _.hashCode,
    setup = (combinedCompilerJars, _, enclosing) => {
      mill.util.Jvm.createClassLoader(
        combinedCompilerJars.map(_.path),
        parent = parent,
        sharedLoader = sharedLoader,
        sharedPrefixes = sharedPrefixes
      )(using enclosing)
    },
    closeValue = cl => {
      extraRelease(cl)
      cl.close()
    }
  )

  def extraRelease(cl: ClassLoader): Unit = ()

  def release(combinedCompilerJars: Seq[PathRef]): Option[(URLClassLoader, Int)] =
    cache.release(combinedCompilerJars).map { case RefCountedCache.Entry(value, refCount) =>
      (value, refCount)
    }

  def get(combinedCompilerJars: Seq[PathRef])(using e: sourcecode.Enclosing): URLClassLoader =
    cache.get(combinedCompilerJars, e)

  override def close(): Unit =
    cache.close()
}
