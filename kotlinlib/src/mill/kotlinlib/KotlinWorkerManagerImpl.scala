/*
 * Original code copied from https://github.com/lefou/mill-kotlin
 * Original code published under the Apache License Version 2
 * Original Copyright 2020-2024 Tobias Roeser
 */
package mill.kotlinlib

import mill.PathRef
import mill.api.Ctx
import mill.kotlinlib.worker.api.KotlinWorker

import java.net.{URL, URLClassLoader}

class KotlinWorkerManagerImpl(ctx: Ctx) extends KotlinWorkerManager with AutoCloseable {

  private var workerCache: Map[Seq[PathRef], (KotlinWorker, Int)] = Map.empty

  override def get(toolsClasspath: Seq[PathRef])(implicit ctx: Ctx): KotlinWorker = {
    val toolsCp = toolsClasspath.distinct
    val (worker, count) = workerCache.get(toolsCp) match {
      case Some((w, count)) =>
        ctx.log.debug(s"Reusing existing KotlinWorker for classpath: ${toolsCp}")
        w -> count
      case None =>
        ctx.log.debug(s"Creating Classloader with classpath: [${toolsCp}]")
        val classLoader = new URLClassLoader(
          toolsCp.map(_.path.toNIO.toUri().toURL()).toArray[URL],
          getClass().getClassLoader()
        )

        val className =
          classOf[KotlinWorker].getPackage().getName().split("\\.").dropRight(1).mkString(
            "."
          ) + ".impl." + classOf[KotlinWorker].getSimpleName() + "Impl"
        ctx.log.debug(s"Creating ${className} from classpath: ${toolsCp}")
        val impl = classLoader.loadClass(className)
        val worker = impl.getConstructor().newInstance().asInstanceOf[KotlinWorker]
        if (worker.getClass().getClassLoader() != classLoader) {
          ctx.log.error(
            """Worker not loaded from worker classloader.
              |You should not add the mill-kotlin-worker JAR to the mill build classpath""".stripMargin
          )
        }
        if (worker.getClass().getClassLoader() == classOf[KotlinWorker].getClassLoader()) {
          ctx.log.error("Worker classloader used to load interface and implementation")
        }
        worker -> 0
    }
    workerCache += toolsCp -> (worker -> (1 + count))
    ctx.log.debug(stats())
    worker
  }

  def stats(): String = {
    s"""Cache statistics of ${this.toString()}:
       |${
        workerCache.map { case (cp, (worker, count)) =>
          s"""- worker: ${worker.toString()}
             |  used: ${count}
             |""".stripMargin
        }.mkString
      }""".stripMargin
  }

  override def close(): Unit = {
    ctx.log.debug(stats())

    // We drop cached worker instances
    workerCache = Map.empty
  }
}
