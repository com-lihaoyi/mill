/*
 * Original code copied from https://github.com/lefou/mill-kotlin
 * Original code published under the Apache License Version 2
 * Original Copyright 2020-2024 Tobias Roeser
 */
package mill.kotlinlib

import mill._
import mill.define.{Discover, ExternalModule, TaskCtx}
import mill.kotlinlib.worker.api.KotlinWorker
import mill.util.CachedFactory

import java.net.{URL, URLClassLoader}
class KotlinWorkerFactory()(implicit ctx: TaskCtx)
    extends CachedFactory[Seq[PathRef], (URLClassLoader, KotlinWorker)] {

  private val classloaderCache = new mill.util.RefCountedClassLoaderCache(
    parent = getClass.getClassLoader
  )

  def setup(key: Seq[PathRef]) = {

    val cl = classloaderCache.get(key)
    val worker =
      try KotlinWorkerManager.get(cl)
      catch { case e => e.printStackTrace(); ??? }
    (cl, worker)
  }

  override def teardown(key: Seq[PathRef], value: (URLClassLoader, KotlinWorker)): Unit = {
    classloaderCache.release(key)
  }

  override def maxCacheSize: Int = ctx.jobs

  override def close(): Unit = {
    classloaderCache.close()
    super.close()
  }
}

object KotlinWorkerManager extends ExternalModule {
  def kotlinWorker: Worker[KotlinWorkerFactory] = Task.Worker {
    new KotlinWorkerFactory()
  }

  def get(toolsClassLoader: URLClassLoader)(implicit ctx: TaskCtx): KotlinWorker = {
    val className =
      classOf[KotlinWorker].getPackage().getName().split("\\.").dropRight(1).mkString(
        "."
      ) + ".impl." + classOf[KotlinWorker].getSimpleName() + "Impl"

    val impl = toolsClassLoader.loadClass(className)
    val worker = impl.getConstructor().newInstance().asInstanceOf[KotlinWorker]
    if (worker.getClass().getClassLoader() != toolsClassLoader) {
      ctx.log.warn(
        """Worker not loaded from worker classloader.
          |You should not add the mill-kotlin-worker JAR to the mill build classpath""".stripMargin
      )
    }
    if (worker.getClass().getClassLoader() == classOf[KotlinWorker].getClassLoader()) {
      ctx.log.warn("Worker classloader used to load interface and implementation")
    }
    worker
  }

  override protected def millDiscover: Discover = Discover[this.type]
}
