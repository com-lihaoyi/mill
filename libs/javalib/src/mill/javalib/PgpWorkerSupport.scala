package mill.javalib

import mill.*
import mill.util.Jvm

import scala.annotation.nowarn

private[mill] trait PgpWorkerSupport extends CoursierModule with OfflineSupportModule {
  private def pgpWorkerClasspath: T[Seq[PathRef]] = Task {
    defaultResolver().classpath(Seq(
      Dep.millProjectModule("mill-libs-javalib-worker")
    ))
  }

  override def prepareOffline(all: mainargs.Flag): Task.Command[Seq[PathRef]] = Task.Command {
    (super.prepareOffline(all)() ++ pgpWorkerClasspath()).distinct
  }

  private def pgpWorkerClassloader: Task.Worker[ClassLoader & AutoCloseable] = Task.Worker {
    val classPath = pgpWorkerClasspath().map(_.path)
    Jvm.createClassLoader(classPath = classPath, parent = getClass.getClassLoader)
  }

  @nowarn("msg=.*Workers should implement AutoCloseable.*")
  private[mill] def pgpWorker: Task.Worker[mill.javalib.api.PgpWorkerApi] =
    Task.Worker {
      pgpWorkerClassloader().loadClass("mill.javalib.worker.PgpSignerWorker")
        .getConstructor().newInstance().asInstanceOf[mill.javalib.api.PgpWorkerApi]
    }
}
