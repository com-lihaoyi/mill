package mill.util

import mill.define.PathRef

import java.net.URLClassLoader
import collection.mutable.LinkedHashMap

class RefCountedClassLoaderCache(
    sharedLoader: ClassLoader = null,
    sharedPrefixes: Seq[String] = Nil,
    parent: ClassLoader = null
) {

  val cache = LinkedHashMap.empty[Long, (URLClassLoader, Int)]

  def release(combinedCompilerJars: Seq[PathRef]) = synchronized {
    val compilersSig = combinedCompilerJars.hashCode()
    cache.updateWith(compilersSig) {
      case Some((cl, 1)) =>
        // We try to find the timer created by scala.tools.nsc.classpath.FileBasedCache
        // and cancel it, so that it shuts down its thread.
        for {
          cls <- {
            try Some(cl.loadClass("scala.tools.nsc.classpath.FileBasedCache$"))
            catch {
              case _: ClassNotFoundException => None
            }
          }
          moduleField <- {
            try Some(cls.getField("MODULE$"))
            catch {
              case _: NoSuchFieldException => None
            }
          }
          module = moduleField.get(null)
          timerField <- {
            try Some(cls.getDeclaredField("scala$tools$nsc$classpath$FileBasedCache$$timer"))
            catch {
              case _: NoSuchFieldException => None
            }
          }
          _ = timerField.setAccessible(true)
          timerOpt0 = timerField.get(module)
          getOrElseMethod <- timerOpt0.getClass.getMethods.find(_.getName == "getOrElse")
          timer <-
            Option(getOrElseMethod.invoke(timerOpt0, null).asInstanceOf[java.util.Timer])
        } {

          timer.cancel()
        }

        cl.close()
        None
      case Some((cl, n)) if n > 1 => Some((cl, n - 1))
      case _ => ??? // No other cases; n should never be zero or negative
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

  def close() = {
    cache.values.map { case (cl, hash) => cl.close() }
    cache.clear()
  }

}
