/*
 * Original code copied from https://github.com/lefou/mill-kotlin
 * Original code published under the Apache License Version 2
 * Original Copyright 2020-2024 Tobias Roeser
 */
package mill.kotlinlib

import mill.*
import mill.api.{Discover, ExternalModule, TaskCtx}
import mill.kotlinlib.worker.api.KotlinWorker
import mill.util.ClassLoaderCachedFactory

class KotlinWorkerManager()(implicit ctx: TaskCtx)
    extends ClassLoaderCachedFactory[KotlinWorker](ctx.jobs) {

  def getValue(cl: ClassLoader) = KotlinWorkerManager.get(cl)
}

object KotlinWorkerManager extends ExternalModule {
  def kotlinWorker: Worker[KotlinWorkerManager] = Task.Worker {
    new KotlinWorkerManager()
  }

  def get(toolsClassLoader: ClassLoader)(implicit ctx: TaskCtx): KotlinWorker = {
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
