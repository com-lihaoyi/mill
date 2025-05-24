package mill.scalalib.spotless

import mainargs.Flag
import mill.define.*
import mill.scalalib.{CoursierModule, Dep, OfflineSupportModule}
import mill.util.Jvm

import java.net.URLClassLoader

@mill.api.experimental
trait SpotlessWorkerModule extends CoursierModule, OfflineSupportModule {

  /**
   * Formats to be applied, typically one per programming language.
   * Defaults to value in workspace file `spotless-formats.json`, if it exists,
   * else [[Format.defaults]].
   */
  def formats: Task[Seq[Format]] = Task.Input {
    BuildCtx.withFilesystemCheckerDisabled {
      val file = Task.workspace / "spotless-formats.json"
      if os.exists(file) then Format.readAll(file)
      else Format.defaults
    }
  }

  def workerClasspath: Task[Seq[PathRef]] = Task {
    defaultResolver().classpath(Seq(Dep.millProjectModule("mill-libs-scalalib-spotless-worker")))
  }

  def workerClassloader: Task.Worker[URLClassLoader] = Task.Worker {
    Jvm.createClassLoader(
      classPath = workerClasspath().map(_.path),
      parent = getClass.getClassLoader
    )
  }

  def worker: Task.Worker[SpotlessWorker] = Task.Worker {
    workerClassloader().loadClass(workerImplClass)
      .getConstructor(classOf[Seq[Format]], classOf[CoursierModule.Resolver], classOf[TaskCtx])
      .newInstance(formats(), defaultResolver(), Task.ctx())
      .asInstanceOf[SpotlessWorker]
  }

  override def prepareOffline(all: Flag) = Task.Command {
    (super.prepareOffline(all)() ++ workerClasspath() ++ provision()).distinct
  }

  private def provision = Task.Anon {
    workerClassloader().loadClass(workerImplClass)
      .getMethod(
        "provision",
        classOf[Seq[Format]],
        classOf[CoursierModule.Resolver],
        classOf[TaskCtx]
      )
      .invoke(null, formats(), defaultResolver(), Task.ctx())
      .asInstanceOf[Seq[PathRef]]
  }

  private def workerImplClass = "mill.scalalib.spotless.SpotlessWorkerImpl"
}
@mill.api.experimental
object SpotlessWorkerModule extends ExternalModule, SpotlessWorkerModule {
  lazy val millDiscover = Discover[this.type]
}
