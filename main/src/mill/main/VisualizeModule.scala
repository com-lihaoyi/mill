package mill.main

import java.util.concurrent.LinkedBlockingQueue
import coursier.LocalRepositories
import coursier.core.Repository
import coursier.maven.MavenRepository
import mill.define.{
  Discover,
  Evaluator,
  ExternalModule,
  MultiBiMap,
  NamedTask,
  SelectMode,
  Target,
  Task,
  Worker
}
import mill.util.MillModuleUtil.millProjectModule
import mill.api.{PathRef, Result}
import org.jgrapht.graph.{DefaultEdge, SimpleDirectedGraph}
import guru.nidi.graphviz.attribute.Rank.RankDir
import guru.nidi.graphviz.attribute.{Rank, Shape, Style}

object VisualizeModule extends ExternalModule {
  def repositories: Seq[Repository] = Seq(
    LocalRepositories.ivy2Local,
    MavenRepository("https://repo1.maven.org/maven2"),
    MavenRepository("https://oss.sonatype.org/content/repositories/releases")
  )

  lazy val millDiscover = Discover[this.type]

  private type VizWorker = (
      LinkedBlockingQueue[(
          scala.Seq[NamedTask[Any]],
          scala.Seq[NamedTask[Any]],
          MultiBiMap[NamedTask[Any], Task[?]],
          mill.define.Plan,
          os.Path
      )],
      LinkedBlockingQueue[Result[scala.Seq[PathRef]]]
  )

  private[mill] def visualize0(
      evaluator: Evaluator,
      targets: Seq[String],
      ctx: mill.api.Ctx,
      vizWorker: VizWorker,
      planTasks: Option[List[NamedTask[?]]] = None
  ): Result[Seq[PathRef]] = {
    def callVisualizeModule(
        tasks: List[NamedTask[Any]],
        transitiveTasks: List[NamedTask[Any]]
    ): Result[Seq[PathRef]] = {
      val (in, out) = vizWorker
      val transitive = evaluator.transitiveTargets(tasks)
      val topoSorted = evaluator.topoSorted(transitive)
      val sortedGroups = evaluator.groupAroundImportantTargets(topoSorted) {
        case x: NamedTask[Any] if transitiveTasks.contains(x) => x
      }
      val plan = evaluator.plan(transitiveTasks)
      in.put((tasks, transitiveTasks, sortedGroups, plan, ctx.dest))
      val res = out.take()
      res.map { v =>
        println(upickle.default.write(v.map(_.path.toString()), indent = 2))
        v
      }
    }

    evaluator.resolveTasks(targets, SelectMode.Multi).flatMap {
      rs =>
        planTasks match {
          case Some(allRs) => callVisualizeModule(rs, allRs)
          case None => callVisualizeModule(rs, rs)
        }
    }
  }

  @deprecated("Use toolsClasspath instead", "0.13.0-M1")
  def classpath = toolsClasspath

  def toolsClasspath: Target[Seq[PathRef]] = Target {
    millProjectModule("mill-main-graphviz", repositories)
  }

  /**
   * The J2V8-based Graphviz library has a limitation that it can only ever
   * be called from a single thread. Since Mill forks off a new thread every
   * time you execute something, we need to keep around a worker thread that
   * everyone can use to call into Graphviz, which the Mill execution threads
   * can communicate via in/out queues.
   */
  private[mill] def worker: Worker[(
      LinkedBlockingQueue[(
          scala.Seq[NamedTask[Any]],
          scala.Seq[NamedTask[Any]],
          MultiBiMap[NamedTask[Any], Task[?]],
          mill.define.Plan,
          os.Path
      )],
      LinkedBlockingQueue[Result[Seq[PathRef]]]
  )] = mill.define.Task.Worker {
    val in =
      new LinkedBlockingQueue[(
          scala.Seq[NamedTask[Any]],
          scala.Seq[NamedTask[Any]],
          MultiBiMap[NamedTask[Any], Task[?]],
          mill.define.Plan,
          os.Path
      )]()
    val out = new LinkedBlockingQueue[Result[Seq[PathRef]]]()
    val visualizeThread = new java.lang.Thread(() =>
      while (true) {
        val res = Result.Success {
          val (tasks, transitiveTasks, sortedGroups, plan, dest) = in.take()

          val goalSet = transitiveTasks.toSet
          import guru.nidi.graphviz.model.Factory._
          val edgesIterator =
            for ((k, vs) <- sortedGroups.items())
              yield (
                k,
                for {
                  v <- vs
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
            node(plan.sortedGroups.lookupValue(t).toString)
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

          mill.util.Jvm.callProcess(
            mainClass = "mill.main.graphviz.GraphvizTools",
            classPath = toolsClasspath().map(_.path).toVector,
            mainArgs = Seq(s"${os.temp(g.toString)};$dest;txt,dot,json,png,svg"),
            stdin = os.Inherit,
            stdout = os.Inherit
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
