package mill.api.daemon.internal

import mill.api.daemon.internal.TaskApi

trait MillBuildRootModuleApi {

  def bspScriptIgnoreAll: TaskApi[Seq[String]]
}

object MillBuildRootModuleApi {
  val defaultBspScriptIgnore: Seq[String] = Seq(
    "**/src/",
    "**/src-*/",
    "**/resources/",
    "**/out/",
    "**/.bsp/mill-bsp-out/",
    "**/target/"
  )

  def bspScriptIgnore(evaluators: Seq[EvaluatorApi]): Seq[String] = {
    evaluators.iterator
      .flatMap { ev =>
        val bspScriptIgnoreTasks: Seq[TaskApi[Seq[String]]] =
          Seq(ev.rootModule).collect { case m: MillBuildRootModuleApi => m.bspScriptIgnoreAll }

        if (bspScriptIgnoreTasks.nonEmpty) {
          Some(
            ev.executeApi(bspScriptIgnoreTasks)
              .values
              .get
              .flatMap { case sources: Seq[String] => sources }
          )
        } else None
      }
      .nextOption()
      .getOrElse(defaultBspScriptIgnore)
  }
}
