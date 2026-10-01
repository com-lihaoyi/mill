package mill.api.daemon

import mill.api.daemon.internal.PathRefApi

import java.net.URLClassLoader
import scala.collection.mutable

trait ClassLoaderCache {
  def get(classPath: Seq[PathRefApi]): URLClassLoader
  def release(classPath: Seq[PathRefApi]): Unit
}

/** Special-cased cache handling to let us share classloaders across meta-build boundaries */
object ClassLoaderCache {
  val sharedCompiler: ClassLoaderCache = new ClassLoaderCache {
    private case class Key(paths: Seq[(java.nio.file.Path, Boolean, Int)])
    private case class Entry(classLoader: MillURLClassLoader, var refCount: Int)

    private val cache = mutable.Map.empty[Key, Entry]

    override def get(classPath: Seq[PathRefApi]): URLClassLoader = synchronized {
      val key = Key(classPath.map(pr => (pr.javaPath, pr.quick, pr.sig)))
      cache.get(key) match {
        case Some(entry) =>
          entry.refCount += 1
          entry.classLoader
        case None =>
          val classLoader = new MillURLClassLoader(
            classPath = classPath.map(_.javaPath),
            parent = null,
            sharedLoader = this.getClass.getClassLoader,
            sharedPrefixes = Seq("xsbti"),
            label = "mill.javalib.zinc.ZincWorker#scalaCompilerCache $anon#setup classLoader"
          )
          cache(key) = Entry(classLoader, 1)
          classLoader
      }
    }

    override def release(classPath: Seq[PathRefApi]): Unit = {
      val closeOpt = synchronized {
        val key = Key(classPath.map(pr => (pr.javaPath, pr.quick, pr.sig)))
        cache.get(key) match {
          case Some(entry) =>
            entry.refCount -= 1
            if (entry.refCount == 0) {
              cache.remove(key)
              Some(entry.classLoader)
            } else None
          case None => None
        }
      }

      closeOpt.foreach { cl =>
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
        cl.close()
      }
    }
  }
}
