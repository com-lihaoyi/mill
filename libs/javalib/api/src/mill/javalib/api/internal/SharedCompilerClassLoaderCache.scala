package mill.javalib.api.internal

import mill.api.PathRef
import mill.api.daemon.ClassLoaderCache
import mill.api.daemon.internal.PathRefApi
import mill.util.RefCountedClassLoaderCache

object SharedCompilerClassLoaderCache extends ClassLoaderCache {
  private val underlying = new RefCountedClassLoaderCache(
    sharedLoader = this.getClass.getClassLoader,
    sharedPrefixes = Seq("xsbti")
  ) {
    override def extraRelease(cl: ClassLoader): Unit =
      try {
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
      } catch {
        case _: LinkageError => ()
      }
  }

  override def get(classPath: Seq[PathRefApi]) =
    underlying.get(classPath.map(_.asInstanceOf[PathRef]))

  override def release(classPath: Seq[PathRefApi]): Unit = {
    val _ = underlying.release(classPath.map(_.asInstanceOf[PathRef]))
    ()
  }
}
