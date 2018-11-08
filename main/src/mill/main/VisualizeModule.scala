package mill.main

import java.util.concurrent.LinkedBlockingQueue

import coursier.Cache
import coursier.core.Repository
import coursier.maven.MavenRepository
import mill.T
import mill.define.{Discover, ExternalModule}
import mill.eval.{PathRef, Result}

object VisualizeModule extends ExternalModule with VisualizeModule {
  def repositories = Seq(
    Cache.ivy2Local,
    MavenRepository("https://repo1.maven.org/maven2"),
    MavenRepository("https://oss.sonatype.org/content/repositories/releases")
  )

  implicit def millScoptEvaluatorReads[T] = new mill.main.EvaluatorScopt[T]()
  lazy val millDiscover = Discover[this.type]
}
trait VisualizeModule extends mill.define.TaskModule{
  def repositories: Seq[Repository]
  def defaultCommandName() = "run"
  def classpath = T{
    mill.modules.Util.millProjectModule("MILL_GRAPHVIZ", "mill-main-graphviz", repositories)
  }

  /**
    * The J2V8-based Graphviz library has a limitation that it can only ever
    * be called from a single thread. Since Mill forks off a new thread every
    * time you execute something, we need to keep around a worker thread that
    * everyone can use to call into Graphviz, which the Mill execution threads
    * can communicate via in/out queues.
    */
  def worker = T.worker{
    val in = new LinkedBlockingQueue[(Seq[_], Seq[_], os.Path)]()
    val out = new LinkedBlockingQueue[Result[Seq[PathRef]]]()

    val cl = mill.util.ClassLoader.create(
      classpath().map(_.path.toNIO.toUri.toURL).toVector,
      getClass.getClassLoader
    )
    val visualizeThread = new java.lang.Thread(() =>
      while(true){
        val res = Result.create{
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
