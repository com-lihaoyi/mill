/*
 * Original code copied from https://github.com/lefou/mill-kotlin
 * Original code published under the Apache License Version 2
 * Original Copyright 2020-2024 Tobias Roeser
 */
package mill.kotlinlib

import mill.PathRef
import mill.define.TaskCtx
import mill.kotlinlib.worker.api.KotlinWorker

import java.net.{URL, URLClassLoader}

object KotlinWorkerManager {

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

}
