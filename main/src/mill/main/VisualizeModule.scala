package mill.main

import java.util.concurrent.LinkedBlockingQueue
import coursier.LocalRepositories
import coursier.core.Repository
import coursier.maven.MavenRepository
import mill.define.{Discover, ExternalModule, Target}
import mill.api.{PathRef, Result}
import mill.util.Util.millProjectModule
import mill.api.Loose
import mill.define.Worker
import os.Path

object VisualizeModule extends ExternalModule with VisualizeModule {
  def repositories: Seq[Repository] = Seq(
    LocalRepositories.ivy2Local,
    MavenRepository("https://repo1.maven.org/maven2"),
    MavenRepository("https://oss.sonatype.org/content/repositories/releases")
  )

  lazy val millDiscover: Discover[this.type] = Discover[this.type]
}
trait VisualizeModule extends mill.define.TaskModule {
  def repositories: Seq[Repository]
  def defaultCommandName() = "run"
  def classpath: Target[Loose.Agg[PathRef]] = Target {
    millProjectModule("mill-main-graphviz", repositories)
  }

  /**
   * The J2V8-based Graphviz library has a limitation that it can only ever
   * be called from a single thread. Since Mill forks off a new thread every
   * time you execute something, we need to keep around a worker thread that
   * everyone can use to call into Graphviz, which the Mill execution threads
   * can communicate via in/out queues.
   */
  def worker: Worker[(
      LinkedBlockingQueue[(Seq[_], Seq[_], Path)],
      LinkedBlockingQueue[Result[Seq[PathRef]]]
  )] = Target.worker {
    try os.proc("dot").call(stdin = "graph {}")
    catch {
      case e: Exception =>
        throw Result.Failure(
          """graphviz `dot` executable not found for rendering visualization. Please install it via:
            |
            |Mac: brew install graphviz
            |Ubuntu: sudo apt install graphviz
            |""".stripMargin
        )

    }
    val in = new LinkedBlockingQueue[(Seq[_], Seq[_], os.Path)]()
    val out = new LinkedBlockingQueue[Result[Seq[PathRef]]]()

    val cl = mill.api.ClassLoader.create(
      classpath().map(_.path.toNIO.toUri.toURL).toVector,
      getClass.getClassLoader
    )
    val visualizeThread = new java.lang.Thread(() =>
      while (true) {
        val res = Result.Success {
          val (targets, tasks, dest) = in.take()
          cl.loadClass("mill.main.graphviz.GraphvizTools")
            .getMethod("apply", classOf[Seq[_]], classOf[Seq[_]], classOf[os.Path])
            .invoke(null, targets, tasks, dest)
            .asInstanceOf[Seq[PathRef]]
        }
        out.put(res)
      }
    )
    visualizeThread.setDaemon(true)
    visualizeThread.start()
    (in, out)
  }
}
