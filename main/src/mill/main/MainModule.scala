package mill.main

import ammonite.ops.Path
import guru.nidi.graphviz.model.Node
import mill.define.{Graph, NamedTask, Task}
import mill.eval.{Evaluator, Result}
import mill.util.{PrintLogger, Watched}
import org.jgrapht.graph.{DefaultEdge, SimpleDirectedGraph}
import pprint.{Renderer, Truncated}
import upickle.Js

object MainModule{
  def resolveTasks[T](evaluator: Evaluator[Any], targets: Seq[String], multiSelect: Boolean)
                     (f: List[NamedTask[Any]] => T) = {
    RunScript.resolveTasks(mill.main.ResolveTasks, evaluator, targets, multiSelect) match{
      case Left(err) => Result.Failure(err)
      case Right(tasks) => Result.Success(f(tasks))
    }
  }
  def evaluateTasks[T](evaluator: Evaluator[Any], targets: Seq[String], multiSelect: Boolean)
                      (f: Seq[(Any, Option[Js.Value])] => T) = {
    RunScript.evaluateTasks(evaluator, targets, multiSelect) match{
      case Left(err) => Result.Failure(err)
      case Right((watched, Left(err))) => Result.Failure(err, Some(Watched((), watched)))
      case Right((watched, Right(res))) =>
        f(res)
        Result.Success(Watched((), watched))
    }
  }
}

trait MainModule extends mill.Module{

  implicit def millDiscover: mill.define.Discover[_]
  implicit def millScoptTasksReads[T] = new mill.main.Tasks.Scopt[T]()
  implicit def millScoptEvaluatorReads[T] = new mill.main.EvaluatorScopt[T]()
  def version() = mill.T.command {
    val res = System.getProperty("MILL_VERSION")
    println(res)
    res
  }

  private val OutDir: String = "out"

  /**
    * Resolves a mill query string and prints out the tasks it resolves to.
    */
  def resolve(evaluator: Evaluator[Any], targets: String*) = mill.T.command{
    val resolved = RunScript.resolveTasks(
      mill.main.ResolveMetadata, evaluator, targets, multiSelect = true
    )

    resolved match{
      case Left(err) => Result.Failure(err)
      case Right(rs) =>
        for(r <- rs.sorted) {
          println(r)
        }
        Result.Success(())
    }
  }

  /**
    * Given a set of tasks, prints out the execution plan of what tasks will be
    * executed in what order, without actually executing them.
    */
  def plan(evaluator: Evaluator[Any], targets: String*) = mill.T.command{
    val resolved = RunScript.resolveTasks(
      mill.main.ResolveTasks, evaluator, targets, multiSelect = true
    )

    resolved match{
      case Left(err) => Result.Failure(err)
      case Right(rs) =>
        val (sortedGroups, transitive) = Evaluator.plan(evaluator.rootModule, rs)
        val labels = sortedGroups
          .keys()
          .collect{ case Right(r) => r.segments.render}
          .toArray

        labels.foreach(println)
        Result.Success(labels)
    }
  }

  /**
    * Prints out some dependency path from the `src` task to the `dest` task.
    *
    * If there are multiple dependency paths between `src` and `dest`, the path
    * chosen is arbitrary.
    */
  def path(evaluator: Evaluator[Any], src: String, dest: String) = mill.T.command{
    val resolved = RunScript.resolveTasks(
      mill.main.ResolveTasks, evaluator, List(src, dest), multiSelect = true
    )

    resolved match{
      case Left(err) => Result.Failure(err)
      case Right(Seq(src1, dest1)) =>
        val queue = collection.mutable.Queue[List[Task[_]]](List(src1))
        var found = Option.empty[List[Task[_]]]
        val seen = collection.mutable.Set.empty[Task[_]]
        while(queue.nonEmpty && found.isEmpty){
          val current = queue.dequeue()
          if (current.head == dest1) found = Some(current)
          else{
            for{
              next <- current.head.inputs
              if !seen.contains(next)
            }{
              seen.add(next)
              queue.enqueue(next :: current)
            }
          }
        }
        found match{
          case None =>
            Result.Failure(s"No path found between $src and $dest")
          case Some(list) =>
            val labels = list
              .collect{case n: NamedTask[_] => n.ctx.segments.render}

            labels.foreach(mill.T.ctx().log.outputStream.println(_))

            Result.Success(labels)
        }
    }
  }

  /**
    * Displays metadata about the given task without actually running it.
    */
  def inspect(evaluator: Evaluator[Any], targets: String*) = mill.T.command{
    MainModule.resolveTasks(evaluator, targets, multiSelect = true){ tasks =>
      for{
        task <- tasks
        tree = ReplApplyHandler.pprintTask(task, evaluator)
        val defaults = pprint.PPrinter()
        val renderer = new Renderer(
          defaults.defaultWidth,
          defaults.colorApplyPrefix,
          defaults.colorLiteral,
          defaults.defaultIndent
        )
        val rendered = renderer.rec(tree, 0, 0).iter
        val truncated = new Truncated(rendered, defaults.defaultWidth, defaults.defaultHeight)
        str <- truncated ++ Iterator("\n")
      } {
        print(str)
      }
    }
  }

  /**
    * Runs multiple tasks in a single call.
    *
    *
    */
  def all(evaluator: Evaluator[Any], targets: String*) = mill.T.command{
    MainModule.evaluateTasks(evaluator, targets, multiSelect = true) {res =>
      res.flatMap(_._2)
    }
  }

  /**
    * Runs a given task and prints the JSON result to stdout. This is useful
    * to integrate Mill into external scripts and tooling.
    */
  def show(evaluator: Evaluator[Any], targets: String*) = mill.T.command{
    MainModule.evaluateTasks(
      evaluator.copy(
        // When using `show`, redirect all stdout of the evaluated tasks so the
        // printed JSON is the only thing printed to stdout.
        log = evaluator.log match{
          case PrintLogger(c1, c2, o, i, e, in) => PrintLogger(c1, c2, e, i, e, in)
          case l => l
        }
      ),
      targets,
      multiSelect = false
    ) {res =>
      for(json <- res.flatMap(_._2)){
        println(json.render(indent = 4))
      }
    }
  }

  /**
    * Deletes the given targets from the out directory. Providing no targets
    * will clean everything.
    */
  def clean(evaluator: Evaluator[Any], targets: String*) = mill.T.command {
    val rootDir = ammonite.ops.pwd / OutDir

    val KeepPattern = "(mill-.+)".r.anchored

    def keepPath(path: Path) = path.segments.lastOption match {
      case Some(KeepPattern(_)) => true
      case _ => false
    }

    val pathsToRemove =
      if (targets.isEmpty)
        Right(ammonite.ops.ls(rootDir).filterNot(keepPath))
      else
        RunScript.resolveTasks(
          mill.main.ResolveSegments, evaluator, targets, multiSelect = true
        ).map(
          _.map { segments =>
            Evaluator.resolveDestPaths(rootDir, segments).out
          })

    pathsToRemove match {
      case Left(err) =>
        Result.Failure(err)
      case Right(paths) =>
        paths.foreach(ammonite.ops.rm)
        Result.Success(())
    }
  }

  /**
    * Given a set of tasks, prints out the execution plan of what tasks will be
    * executed in what order, without actually executing them.
    */
  def visualize(evaluator: Evaluator[Any], targets: String*) = mill.T.command{
    val resolved = RunScript.resolveTasks(
      mill.main.ResolveTasks, evaluator, targets, multiSelect = true
    )
    resolved match{
      case Left(err) => Result.Failure(err)
      case Right(rs) =>
        val transitive = Graph.transitiveTargets(rs.distinct)
        val topoSorted = Graph.topoSorted(transitive)
        val goalSet = rs.toSet
        val sortedGroups = Graph.groupAroundImportantTargets(topoSorted){
          case x: NamedTask[Any] if goalSet.contains(x) => x
        }
        import guru.nidi.graphviz.model.Factory._
        import guru.nidi.graphviz.engine.{Format, Graphviz}

        val edgesIterator =
          for((k, vs) <- sortedGroups.items())
          yield (
            k,
            for {
              v <- vs.items
              dest <- v.inputs.collect { case v: NamedTask[Any] => v }
              if goalSet.contains(dest)
            } yield dest
          )

        val edges = edgesIterator.map{case (k, v) => (k, v.toArray.distinct)}.toArray

        val indexToTask = edges.flatMap{case (k, vs) => Iterator(k) ++ vs}.distinct
        val taskToIndex = indexToTask.zipWithIndex.toMap

        val jgraph = new SimpleDirectedGraph[Int, DefaultEdge](classOf[DefaultEdge])

        for(i <- indexToTask.indices) jgraph.addVertex(i)
        for((src, dests) <- edges; dest <- dests) {
          jgraph.addEdge(taskToIndex(src), taskToIndex(dest))
        }


        org.jgrapht.alg.TransitiveReduction.INSTANCE.reduce(jgraph)
        val nodes = indexToTask.map(t => node(t.ctx.segments.render))

        var g = graph("example1").directed
        for(i <- indexToTask.indices){
          val outgoing = for{
            e <- edges(i)._2
            j = taskToIndex(e)
            if jgraph.containsEdge(i, j)
          } yield nodes(j)
          g = g.`with`(nodes(i).link(outgoing:_*))
        }

        val gv = Graphviz.fromGraph(g).totalMemory(100 * 1000 * 1000)
        val outputs = Seq(
          Format.PLAIN -> "out.txt",
          Format.XDOT -> "out.dot",
          Format.JSON -> "out.json",
          Format.PNG -> "out.png",
          Format.SVG -> "out.svg"
        )
        for((fmt, name) <- outputs) {
          gv.render(fmt).toFile((mill.T.ctx().dest / name).toIO)
        }
        Result.Success(outputs.map(x => mill.PathRef(mill.T.ctx().dest / x._2)))
    }
  }

}
