package mill.main

import java.util.concurrent.LinkedBlockingQueue
import coursier.LocalRepositories
import coursier.core.Repository
import coursier.maven.MavenRepository
import mill.define.{Discover, ExternalModule, NamedTask, Target}
import mill.util.Util.millProjectModule
import mill.api.{Loose, PathRef, Result}
import mill.define.Worker
import org.jgrapht.graph.{DefaultEdge, SimpleDirectedGraph}
import guru.nidi.graphviz.attribute.Rank.RankDir
import guru.nidi.graphviz.attribute.{Rank, Shape, Style}
import mill.eval.Graph

object VisualizeModule extends ExternalModule with VisualizeModule {
  def repositories: Seq[Repository] = Seq(
    LocalRepositories.ivy2Local,
    MavenRepository("https://repo1.maven.org/maven2"),
    MavenRepository("https://oss.sonatype.org/content/repositories/releases")
  )

  lazy val millDiscover: Discover = Discover[this.type]
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
      LinkedBlockingQueue[(Seq[NamedTask[Any]], Seq[NamedTask[Any]], os.Path)],
      LinkedBlockingQueue[Result[Seq[PathRef]]]
  )] = Target.worker {
    val in = new LinkedBlockingQueue[(Seq[NamedTask[Any]], Seq[NamedTask[Any]], os.Path)]()
    val out = new LinkedBlockingQueue[Result[Seq[PathRef]]]()

    val visualizeThread = new java.lang.Thread(() =>
      while (true) {
        val res = Result.Success {
          val (tasks, transitiveTasks, dest) = in.take()
          val transitive = Graph.transitiveTargets(tasks)
          val topoSorted = Graph.topoSorted(transitive)
          val sortedGroups = Graph.groupAroundImportantTargets(topoSorted) {
            case x: NamedTask[Any] if transitiveTasks.contains(x) => x
          }
          val (plannedForRender, _) = mill.eval.Plan.plan(transitiveTasks)

          val goalSet = transitiveTasks.toSet
          import guru.nidi.graphviz.model.Factory._
          val edgesIterator =
            for ((k, vs) <- sortedGroups.items())
              yield (
                k,
                for {
                  v <- vs.items
                  dest <- v.inputs.collect { case v: mill.define.NamedTask[Any] => v }
                  if goalSet.contains(dest)
                } yield dest
              )

          val edges = edgesIterator.map { case (k, v) => (k, v.toArray.distinct) }.toArray

          val indexToTask = edges.flatMap { case (k, vs) => Iterator(k) ++ vs }.distinct
          val taskToIndex = indexToTask.zipWithIndex.toMap

          val jgraph = new SimpleDirectedGraph[Int, DefaultEdge](classOf[DefaultEdge])

          for (i <- indexToTask.indices) jgraph.addVertex(i)
          for ((src, dests) <- edges; dest <- dests) {
            jgraph.addEdge(taskToIndex(src), taskToIndex(dest))
          }

          org.jgrapht.alg.TransitiveReduction.INSTANCE.reduce(jgraph)
          val nodes = indexToTask.map(t =>
            node(plannedForRender.lookupValue(t).render)
              .`with` {
                if (tasks.contains(t)) Style.SOLID
                else Style.DASHED
              }
              .`with`(Shape.BOX)
          )

          var g = graph("example1").directed
          for (i <- indexToTask.indices) {
            for {
              e <- edges(i)._2
              j = taskToIndex(e)
              if jgraph.containsEdge(i, j)
            } {
              g = g.`with`(nodes(j).link(nodes(i)))
            }
          }

          g = g.graphAttr().`with`(Rank.dir(RankDir.LEFT_TO_RIGHT))

          mill.util.Jvm.spawn(
            "mill.main.graphviz.GraphvizTools",
            classpath().map(_.path),
            mainArgs = Seq(s"${os.temp(g.toString)};$dest;txt,dot,json,png,svg")
          )

          os.list(dest).sorted.map(PathRef(_))
        }
        out.put(res)
      }
    )
    visualizeThread.setDaemon(true)
    visualizeThread.start()
    (in, out)
  }
}
