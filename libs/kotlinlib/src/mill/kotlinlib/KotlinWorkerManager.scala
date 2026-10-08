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
import mill.kotlinlib.worker.api.renderIntAsHex

class KotlinWorkerManager()(using ctx: TaskCtx)
    extends ClassLoaderCachedFactory[KotlinWorker](ctx.jobs) {

  @deprecated("Use the other overload instead.", "Mill after 1.2.0-RC1")
  override def getValue(cachedClassLoader: ClassLoader): KotlinWorker =
    KotlinWorkerManager.get(cachedClassLoader)

  override def getValue(cachedClassLoader: ClassLoader, classpath: Seq[PathRef]): KotlinWorker =
    KotlinWorkerManager.get(
      toolsClassLoader = cachedClassLoader,
      cachePathHashCode = classpath.map(_.sig).hashCode(),
      cachePathIsStable = true
    )
}

object KotlinWorkerManager extends ExternalModule {
  def kotlinWorker: Worker[KotlinWorkerManager] = Task.Worker {
    KotlinWorkerManager()
  }

  @deprecated("Use the other overload instead.", "Mill after 1.2.0-RC1")
  def get(toolsClassLoader: ClassLoader)(using ctx: TaskCtx): KotlinWorker =
    get(toolsClassLoader, toolsClassLoader.hashCode(), cachePathIsStable = false)

  private def get(
      toolsClassLoader: ClassLoader,
      cachePathHashCode: Int,
      cachePathIsStable: Boolean
  )(using
      ctx: TaskCtx
  ): KotlinWorker = {
    val className =
      classOf[KotlinWorker].getPackage().getName().split("\\.").dropRight(1).mkString(
        "."
      ) + ".impl." + classOf[KotlinWorker].getSimpleName() + "Impl"

    val impl = toolsClassLoader.loadClass(className)
    // FIXME: this prevents reuse over JVM restarts but is safe with different snapshotting algorithms in different
    //  classloaders
    val classpathSnapshotCache =
      ctx.dest / s"${
          if (cachePathIsStable) "stable-" else ""
        }classpath-snapshots" / renderIntAsHex(cachePathHashCode)

    val worker = impl
      .getConstructor(
        classOf[os.Path],
        classOf[Boolean]
      )
      .newInstance(classpathSnapshotCache, cachePathIsStable)
      .asInstanceOf[KotlinWorker]

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

  override def millDiscover: Discover = Discover[this.type]
}
