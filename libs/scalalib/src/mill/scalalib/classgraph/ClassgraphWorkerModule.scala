package mill.scalalib.classgraph

import mainargs.Flag
import mill.{Command, T, Task}
import mill.define.{TaskCtx, PathRef}
import mill.define.{Discover, ExternalModule, Worker}
import mill.scalalib.{CoursierModule, OfflineSupportModule, Dep}
import mill.util.Jvm

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

  private def classgraphWorkerClassloader: Worker[ClassLoader] = Task.Worker {
    Jvm.createClassLoader(
      classPath = classgraphWorkerClasspath().map(_.path),
      parent = getClass().getClassLoader()
    )
  }

  def classgraphWorker: Worker[ClassgraphWorker] = Task.Worker {
    classgraphWorkerClassloader()
      .loadClass("mill.scalalib.classgraph.impl.ClassgraphWorkerImpl")
      .getConstructor().newInstance().asInstanceOf[ClassgraphWorker]
  }

}

object ClassgraphWorkerModule extends ExternalModule with ClassgraphWorkerModule {
  override protected def millDiscover: Discover = Discover[this.type]
}
