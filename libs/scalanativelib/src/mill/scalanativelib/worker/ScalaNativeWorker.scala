package mill.scalanativelib.worker

import mill.define.{Discover}
import mill.{PathRef, Task, Worker}
import mill.scalanativelib.worker.{api => workerApi}
import mill.util.CachedFactory

import java.net.URLClassLoader

private[scalanativelib] class ScalaNativeWorker(jobs: Int)
    extends CachedFactory[Seq[mill.PathRef], (URLClassLoader, workerApi.ScalaNativeWorkerApi)] {
  override def setup(key: Seq[PathRef]) = {

    val cl = classloaderCache.get(key)
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
    classloaderCache.release(key)
  }

  override def close(): Unit = {
    classloaderCache.close()
    super.close()
  }

  override def maxCacheSize: Int = jobs

  private val classloaderCache =
    new mill.util.RefCountedClassLoaderCache(parent = getClass.getClassLoader)
}

private[scalanativelib] object ScalaNativeWorkerExternalModule extends mill.define.ExternalModule {
  def scalaNativeWorker: Worker[ScalaNativeWorker] =
    Task.Worker { new ScalaNativeWorker(Task.ctx().jobs) }
  lazy val millDiscover = Discover[this.type]
}
