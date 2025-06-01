package mill.scalalib.spotless

import mainargs.Flag
import mill.define.*
import mill.scalalib.{CoursierModule, Dep, OfflineSupportModule}
import mill.util.Jvm

/**
 * Implementations can override [[repositoriesTask]] to resolve artifacts required by a [[Format.Step]].
 */
@mill.api.experimental // see notes in package object
trait SpotlessWorkerModule extends CoursierModule, OfflineSupportModule {

  /**
   * Formats to be applied, typically one per programming language. Defaults to value encoded as
   * `upickle` "spotless-formats.json" if it exists. Otherwise, [[Format.defaults]].
   */
  def formats = Task {
    val path = Task.workspace / "spotless-formats.json"
    if os.exists(path) then Format.readAll(path) else Format.defaults
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
@mill.api.experimental // see notes in package object
object SpotlessWorkerModule extends ExternalModule, SpotlessWorkerModule {
  lazy val millDiscover = Discover[this.type]
}
