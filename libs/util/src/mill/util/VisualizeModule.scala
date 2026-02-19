package mill.util

import java.util.concurrent.LinkedBlockingQueue
import coursier.core.Repository
import mill.api.{BuildCtx, Discover, Evaluator, ExternalModule, MultiBiMap, PathRef, SelectMode}
import mill.api.Result
import mill.*

import scala.annotation.nowarn
import scala.collection.mutable

object VisualizeModule extends ExternalModule {
  def repositories: Seq[Repository] =
    coursier.Resolve.defaultRepositories

  lazy val millDiscover = Discover[this.type]

  // bin-compat shim
  private type VizWorker = (
      LinkedBlockingQueue[(
          scala.Seq[Task.Named[?]],
          scala.Seq[Task.Named[?]],
          MultiBiMap[Task.Named[?], Task[?]],
          mill.api.Plan,
          os.Path
      )],
      LinkedBlockingQueue[Result[scala.Seq[PathRef]]]
  )

  private def writePayload(
      tasks: List[Task.Named[?]],
      transitiveTasks: List[Task.Named[?]],
      sortedGroups: MultiBiMap[Task.Named[?], Task[?]],
      plan: mill.api.Plan
  ): os.Path = {
    val goalSet = transitiveTasks.toSet
    val labels = mutable.HashMap.empty[Task.Named[?], String]

    def labelOf(task: Task.Named[?]): String =
      labels.getOrElseUpdate(
        task,
        plan.sortedGroups.lookupValueOpt(task).map(_.toString).getOrElse(task.toString)
      )

    val edgesJson = ujson.Arr.from(
      sortedGroups.items().iterator.map { case (src, vs) =>
        val dests = mutable.LinkedHashSet.empty[String]
        vs.iterator.foreach { v =>
          v.inputs.iterator.collect { case v: mill.api.Task.Named[?] => v }.foreach { dest =>
            if (goalSet.contains(dest)) dests += labelOf(dest)
          }
        }
        ujson.Obj(
          "src" -> labelOf(src),
          "dests" -> ujson.Arr.from(dests.iterator.map(ujson.Str(_)))
        )
      }
    )

    val payload = ujson.Obj(
      "tasks" -> ujson.Arr.from(tasks.iterator.map(t => ujson.Str(labelOf(t)))),
      "edges" -> edgesJson
    )

    val payloadPath = os.temp(prefix = "mill-visualize-", suffix = ".json")
    os.write.over(payloadPath, payload.render())
    payloadPath
  }

  private[mill] def visualize0(
      evaluator: Evaluator,
      tasks: Seq[String],
      ctx: mill.api.TaskCtx,
      toolsClasspath: Seq[PathRef],
      planTasks: Option[List[Task.Named[?]]]
  ): Result[Seq[PathRef]] = {
    def callVisualizeModule(
        tasks: List[Task.Named[?]],
        transitiveTasks: List[Task.Named[?]]
    ): Result[Seq[PathRef]] = {
      val transitive = evaluator.transitiveTasks(tasks)
      val topoSorted = evaluator.topoSorted(transitive)
      val sortedGroups = evaluator.groupAroundImportantTasks(topoSorted) {
        case x: Task.Named[?] if transitiveTasks.contains(x) => x
      }
      val plan = evaluator.plan(transitiveTasks)
      val payloadPath = writePayload(tasks, transitiveTasks, sortedGroups, plan)
      try {
        mill.util.Jvm.callProcess(
          mainClass = "mill.graphviz.VisualizeWorkerMain",
          classPath = toolsClasspath.map(_.path).toVector,
          mainArgs = Seq(payloadPath.toString, ctx.dest.toString),
          stdin = os.Inherit,
          stdout = os.Inherit
        )(using ctx)

        BuildCtx.withFilesystemCheckerDisabled {
          os.list(ctx.dest).sorted.map(PathRef(_))
        }
      } finally {
        os.remove(payloadPath, checkExists = false)
      }
    }

    evaluator.resolveTasks(tasks, SelectMode.Multi).flatMap { rs =>
      val rendered = planTasks match {
        case Some(allRs) => callVisualizeModule(rs, allRs)
        case None => callVisualizeModule(rs, rs)
      }
      rendered.map { v =>
        println(upickle.write(v.map(_.path.toString()), indent = 2))
        v
      }
    }
  }

  // binary compatibility forwarder
  @deprecated("Use visualize0(..., toolsClasspath, ...) instead", "1.2.0-M1")
  private[mill] def visualize0(
      evaluator: Evaluator,
      tasks: Seq[String],
      ctx: mill.api.TaskCtx,
      vizWorker: VizWorker,
      planTasks: Option[List[Task.Named[?]]] = None
  ): Result[Seq[PathRef]] = {
    def callVisualizeModule(
        tasks: List[Task.Named[?]],
        transitiveTasks: List[Task.Named[?]]
    ): Result[Seq[PathRef]] = {
      val (in, out) = vizWorker
      val transitive = evaluator.transitiveTasks(tasks)
      val topoSorted = evaluator.topoSorted(transitive)
      val sortedGroups = evaluator.groupAroundImportantTasks(topoSorted) {
        case x: Task.Named[?] if transitiveTasks.contains(x) => x
      }
      val plan = evaluator.plan(transitiveTasks)
      in.put((tasks, transitiveTasks, sortedGroups, plan, ctx.dest))
      val res = out.take()
      res.map { v =>
        println(upickle.write(v.map(_.path.toString()), indent = 2))
        v
      }
    }

    evaluator.resolveTasks(tasks, SelectMode.Multi).flatMap { rs =>
      planTasks match {
        case Some(allRs) => callVisualizeModule(rs, allRs)
        case None => callVisualizeModule(rs, rs)
      }
    }
  }

  // bin-compat shim
  @nowarn("msg=.*Workers should implement AutoCloseable.*")
  private[mill] def worker: Worker[VizWorker] = mill.api.Task.Worker {
    val in =
      new LinkedBlockingQueue[(
          scala.Seq[Task.Named[?]],
          scala.Seq[Task.Named[?]],
          MultiBiMap[Task.Named[?], Task[?]],
          mill.api.Plan,
          os.Path
      )]()
    val out = new LinkedBlockingQueue[Result[scala.Seq[PathRef]]]()
    val workerToolsClasspath = toolsClasspath()

    val visualizeThread = new java.lang.Thread(() =>
      while (true) {
        val res = mill.api.ExecResult.catchWrapException {
          val (tasks, transitiveTasks, sortedGroups, plan, dest) = in.take()
          val payloadPath = writePayload(
            tasks.toList,
            transitiveTasks.toList,
            sortedGroups,
            plan
          )
          try {
            val spawned = mill.util.Jvm.spawnProcess(
              mainClass = "mill.graphviz.VisualizeWorkerMain",
              classPath = workerToolsClasspath.map(_.path).toVector,
              mainArgs = Seq(payloadPath.toString, dest.toString),
              stdin = os.Inherit,
              stdout = os.Inherit,
              stderr = os.Inherit
            )
            val exited = spawned.waitFor()
            if (!exited) {
              throw new RuntimeException("visualize worker process did not exit cleanly")
            }
            val exitCode = spawned.exitCode()
            if (exitCode != 0) {
              throw new RuntimeException(s"visualize worker process exited with code $exitCode")
            }
            BuildCtx.withFilesystemCheckerDisabled {
              os.list(dest).sorted.map(PathRef(_))
            }
          } finally {
            os.remove(payloadPath, checkExists = false)
          }
        }
        out.put(res)
      }
    )
    visualizeThread.setDaemon(true)
    visualizeThread.start()
    (in, out)
  }

  // TODO: Remove bin-compat shim in Mill 2.x
  // binary compatibility forwarder
  @deprecated("Use toolsClasspath instead", "0.13.0-M1")
  def classpath = toolsClasspath

  def toolsClasspath: T[Seq[PathRef]] = Task {
    mill.util.Jvm.resolveDependencies(
      repositories = repositories,
      deps = Seq(
        coursier.Dependency(
          coursier.Module(
            coursier.Organization("com.lihaoyi"),
            coursier.ModuleName("mill-libs-graphviz_3")
          ),
          coursier.VersionConstraint(BuildInfo.millVersion)
        )
      ),
      force = Nil
    ).map(_.map(_.withRevalidateOnce))
  }

}
