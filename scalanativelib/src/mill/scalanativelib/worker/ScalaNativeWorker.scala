package mill.scalanativelib.worker

import mill.api.{CacheFactory, Ctx}
import mill.define.{Discover, Worker}
import mill.{Agg, PathRef, Task}
import mill.scalanativelib.worker.api as workerApi

import java.net.URLClassLoader

private[scalanativelib] class ScalaNativeWorker(jobs: Int) extends AutoCloseable {
  object scalaNativeInstanceCache extends CacheFactory[Agg[mill.PathRef], (URLClassLoader, workerApi.ScalaNativeWorkerApi)]{
    override def setup(key: Agg[PathRef]) = {
      val cl = mill.api.ClassLoader.create(
        key.map(_.path.toIO.toURI.toURL).toVector,
        getClass.getClassLoader
      )(new Ctx.Home {override def home = os.home})
      val bridge = cl
        .loadClass("mill.scalanativelib.worker.ScalaNativeWorkerImpl")
        .getDeclaredConstructor()
        .newInstance()
        .asInstanceOf[workerApi.ScalaNativeWorkerApi]
      (cl, bridge)
    }

    override def teardown(key: Agg[PathRef], value: (URLClassLoader, workerApi.ScalaNativeWorkerApi)): Unit = {
      value._1.close()
    }

    override def maxCacheSize: Int = jobs
  }


  override def close(): Unit = {
    // drop instance
  }
}

private[scalanativelib] object ScalaNativeWorkerExternalModule extends mill.define.ExternalModule {
  def scalaNativeWorker: Worker[ScalaNativeWorker] = Task.Worker { new ScalaNativeWorker(Task.ctx.asInstanceOf[mill.api.Ctx.Jobs].jobs) }
  lazy val millDiscover: Discover = Discover[this.type]
}
