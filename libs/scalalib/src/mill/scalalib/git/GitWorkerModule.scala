package mill.scalalib.git

import mainargs.Flag
import mill.define.{Discover, ExternalModule, PathRef, Task}
import mill.scalalib.{CoursierModule, Dep, OfflineSupportModule}
import mill.util.Jvm

import java.net.URLClassLoader

@mill.api.experimental
trait GitWorkerModule extends CoursierModule, OfflineSupportModule {

  def workerClasspath: Task[Seq[PathRef]] = Task {
    defaultResolver().classpath(Seq(Dep.millProjectModule("mill-libs-scalalib-git-worker")))
  }

  def workerClassloader: Task.Worker[URLClassLoader] = Task.Worker {
    Jvm.createClassLoader(
      classPath = workerClasspath().map(_.path),
      parent = getClass.getClassLoader
    )
  }

  def worker: Task.Worker[GitWorker] = Task.Worker {
    workerClassloader().loadClass("mill.scalalib.git.GitWorkerImpl")
      .getConstructor(classOf[os.Path])
      .newInstance(Task.workspace)
      .asInstanceOf[GitWorker]
  }

  override def prepareOffline(all: Flag) = Task.Command {
    (super.prepareOffline(all)() ++ workerClasspath()).distinct
  }
}
@mill.api.experimental
object GitWorkerModule extends ExternalModule, GitWorkerModule {
  lazy val millDiscover = Discover[this.type]
}
