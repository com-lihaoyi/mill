package mill.scalalib.classgraph

import mainargs.Flag
import mill.{Command, T, Task}
import mill.define.{TaskCtx, PathRef}
import mill.define.{Discover, ExternalModule, Worker}
import mill.scalalib.{CoursierModule, OfflineSupportModule, Dep}

trait ClassgraphWorkerModule extends CoursierModule with OfflineSupportModule {

  def classgraphWorkerClasspath: T[Seq[PathRef]] = T {
    defaultResolver().classpath(Seq(
      Dep.millProjectModule("mill-libs-scalalib-classgraph-worker")
    ))
  }

  override def prepareOffline(all: Flag): Command[Seq[PathRef]] = Task.Command {
    (
      super.prepareOffline(all)() ++
        classgraphWorkerClasspath()
    ).distinct
  }

  def classgraphWorker: Worker[ClassgraphWorker] = Task.Worker {
    new ClassgraphWorker with AutoCloseable {
      private val classLoader = mill.util.Jvm.createClassLoader(
        classPath = classgraphWorkerClasspath().map(_.path),
        parent = getClass().getClassLoader()
      )

      private val worker = classLoader
        .loadClass("mill.scalalib.classgraph.impl.ClassgraphWorkerImpl")
        .getConstructor().newInstance().asInstanceOf[ClassgraphWorker]

      override def discoverMainClasses(classpath: Seq[os.Path])(implicit
          ctx: TaskCtx
      ): Seq[String] =
        worker.discoverMainClasses(classpath)

      override def close(): Unit = {
        classLoader.close()
      }
    }
  }

}

object ClassgraphWorkerModule extends ExternalModule with ClassgraphWorkerModule {
  override protected def millDiscover: Discover = Discover[this.type]
}
