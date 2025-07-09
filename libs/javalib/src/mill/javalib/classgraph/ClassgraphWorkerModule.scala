package mill.javalib.classgraph

import mainargs.Flag
import mill.{Command, T, Task}
import mill.api.{PathRef, TaskCtx}
import mill.api.{Discover, ExternalModule}
import mill.javalib.classgraph.ClassgraphWorker
import mill.javalib.{CoursierModule, Dep, OfflineSupportModule}
import mill.util.Jvm

trait ClassgraphWorkerModule extends CoursierModule with OfflineSupportModule {

  def classgraphWorkerClasspath: T[Seq[PathRef]] = Task {
    defaultResolver().classpath(Seq(
      Dep.millProjectModule("mill-libs-javalib-classgraph-worker")
    ))
  }

  override def prepareOffline(all: Flag): Command[Seq[PathRef]] = Task.Command {
    (
      super.prepareOffline(all)() ++
        classgraphWorkerClasspath()
    ).distinct
  }

  private def classgraphWorkerClassloader: Task.Worker[ClassLoader] = Task.Worker {
    Jvm.createClassLoader(
      classPath = classgraphWorkerClasspath().map(_.path),
      parent = getClass().getClassLoader()
    )
  }

  def classgraphWorker: Task.Worker[ClassgraphWorker] = Task.Worker {
    classgraphWorkerClassloader()
      .loadClass("mill.javalib.classgraph.impl.ClassgraphWorkerImpl")
      .getConstructor().newInstance().asInstanceOf[ClassgraphWorker]
  }

}

object ClassgraphWorkerModule extends ExternalModule with ClassgraphWorkerModule {
  override protected def millDiscover: Discover = Discover[this.type]
}
