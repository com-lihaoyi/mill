package mill.main

import mill.api.{PathRef, Result}
import os.Path

import java.lang.reflect.Method
import java.util.concurrent.LinkedBlockingQueue

/**
 * The J2V8-based Graphviz library has a limitation that it can only ever
 * be called from a single thread. Since Mill forks off a new thread every
 * time you execute something, we need to keep around a worker thread that
 * everyone can use to call into Graphviz, which the Mill execution threads
 * can communicate via in/out queues.
 */
class VisualizeModuleWorker(classpath: Seq[os.Path]) extends AutoCloseable {

  val in: LinkedBlockingQueue[(Seq[_], Seq[_], Path)] =
    new LinkedBlockingQueue[(Seq[_], Seq[_], os.Path)]()

  val out: LinkedBlockingQueue[Result[Seq[PathRef]]] =
    new LinkedBlockingQueue[Result[Seq[PathRef]]]()

  private val classLoader = mill.api.ClassLoader.create(
    classpath.map(_.toNIO.toUri.toURL).toVector,
    getClass.getClassLoader
  )

  private val graphvizToolsMethod: Method =
    classLoader.loadClass("mill.main.graphviz.GraphvizTools")
      .getMethod("apply", classOf[Seq[_]], classOf[Seq[_]], classOf[os.Path])

  private var accepting: Boolean = true

  private val visualizeThread = new java.lang.Thread(() =>
    while (!accepting) {
      val res = Result.Success {
        val (targets, tasks, dest) = in.take()
        graphvizToolsMethod
          .invoke(null, targets, tasks, dest)
          .asInstanceOf[Seq[PathRef]]
      }
      out.put(res)
    }
  )
  visualizeThread.setDaemon(true)
  visualizeThread.start()

  override def close(): Unit = {
    accepting = false
    classLoader.close()
  }
}
