package mill.scalanativelib.worker

import mill.api.Ctx
import mill.define.{Discover, Worker}
import mill.{PathRef, Task}
import mill.scalanativelib.worker.{api => workerApi}
import mill.util.CachedFactory

import java.net.URLClassLoader

private[scalanativelib] class ScalaNativeWorker(jobs: Int)
    extends CachedFactory[Seq[mill.PathRef], (URLClassLoader, workerApi.ScalaNativeWorkerApi)] {
  override def setup(key: Seq[PathRef]) = {
    val cl = mill.util.Jvm.createClassLoader(
      key.map(_.path).toVector,
      getClass.getClassLoader
    )
    val bridge = cl
      .loadClass("mill.scalanativelib.worker.ScalaNativeWorkerImpl")
      .getDeclaredConstructor()
      .newInstance()
      .asInstanceOf[workerApi.ScalaNativeWorkerApi]
    (cl, bridge)
  }

  override def teardown(
      key: Seq[PathRef],
      value: (URLClassLoader, workerApi.ScalaNativeWorkerApi)
  ): Unit = {
    value._1.close()
  }

  override def maxCacheSize: Int = jobs
}

private[scalanativelib] object ScalaNativeWorkerExternalModule extends mill.define.ExternalModule {
  def scalaNativeWorker: Worker[ScalaNativeWorker] =
    Task.Worker { new ScalaNativeWorker(Task.ctx().asInstanceOf[mill.api.Ctx.Jobs].jobs) }
  lazy val millDiscover = Discover[this.type]
}
