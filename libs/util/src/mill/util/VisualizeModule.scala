package mill.util

import coursier.core.Repository
import mill.api.{BuildCtx, Discover, Evaluator, ExternalModule, MultiBiMap, PathRef, SelectMode}
import mill.api.Result
import mill.*

import scala.collection.mutable

object VisualizeModule extends ExternalModule {
  def repositories: Seq[Repository] =
    coursier.Resolve.defaultRepositories

  lazy val millDiscover = Discover[this.type]

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
      planTasks: Option[List[Task.Named[?]]] = None
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

  // TODO: Remove bin-compat shim in Mill 2.x
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
