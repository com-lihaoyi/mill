package mill.scalalib.spotless

import mainargs.Flag
import mill.define.*
import mill.scalalib.{CoursierModule, Dep, OfflineSupportModule}
import mill.util.Jvm

/**
 * Implementations can override [[repositoriesTask]] to resolve artifacts required by a [[Format.Step]].
 */
trait SpotlessWorkerModule extends CoursierModule, OfflineSupportModule {

  /**
   * Formats to be applied, typically one per programming language.
   * Defaults to value in workspace file `spotless-formats.json`, if it exists,
   * else [[Format.defaults]].
   */
  def formats = Task {
    val file = Task.workspace / "spotless-formats.json"
    if os.exists(file) then Format.readAll(file) else Format.defaults
  }

  def workerClasspath = Task {
    defaultResolver().classpath(Seq(Dep.millProjectModule("mill-libs-scalalib-spotless-worker")))
  }

  def workerClassloader = Task.Worker {
    Jvm.createClassLoader(
      classPath = workerClasspath().map(_.path),
      parent = getClass.getClassLoader
    )
  }

  def worker = Task.Worker {
    workerClassloader().loadClass("mill.scalalib.spotless.SpotlessWorkerImpl")
      .getConstructor(classOf[Seq[Format]], classOf[CoursierModule.Resolver], classOf[TaskCtx])
      .newInstance(formats(), defaultResolver(), Task.ctx())
      .asInstanceOf[SpotlessWorker]
  }

  override def prepareOffline(all: Flag) = Task.Command {
    (super.prepareOffline(all)() ++ workerClasspath() ++ worker().provision).distinct
  }
}
object SpotlessWorkerModule extends ExternalModule, SpotlessWorkerModule {
  lazy val millDiscover = Discover[this.type]
}
