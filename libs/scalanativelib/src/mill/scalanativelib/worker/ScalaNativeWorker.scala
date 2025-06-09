package mill.scalanativelib.worker

import mill.define.{Discover}
import mill.{PathRef, Task, Worker}
import mill.scalanativelib.worker.{api => workerApi}
import mill.util.CachedFactory

import mill.util.ClassLoaderCachedFactory

import java.net.URLClassLoader

private[scalanativelib] class ScalaNativeWorker(jobs: Int)
    extends ClassLoaderCachedFactory[workerApi.ScalaNativeWorkerApi](jobs) {
  override def getValue(cl: ClassLoader) = cl
    .loadClass("mill.scalanativelib.worker.ScalaNativeWorkerImpl")
    .getDeclaredConstructor()
    .newInstance()
    .asInstanceOf[workerApi.ScalaNativeWorkerApi]
}

private[scalanativelib] object ScalaNativeWorkerExternalModule extends mill.define.ExternalModule {
  def scalaNativeWorker: Worker[ScalaNativeWorker] =
    Task.Worker { new ScalaNativeWorker(Task.ctx().jobs) }
  lazy val millDiscover = Discover[this.type]
}
