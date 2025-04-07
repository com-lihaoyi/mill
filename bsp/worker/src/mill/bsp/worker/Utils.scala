package mill.bsp.worker

import ch.epfl.scala.bsp4j.{
  BuildClient,
  BuildTarget,
  BuildTargetCapabilities,
  BuildTargetIdentifier,
  OutputPathItem,
  OutputPathItemKind,
  StatusCode,
  TaskId
}
import mill.runner.api.CompileProblemReporter
import mill.runner.api.ExecResult.{Skipped, Success}
import mill.runner.api.{TaskApi, JavaModuleApi, BspBuildTarget, BspModuleApi, ExecutionResultsApi}

import scala.jdk.CollectionConverters.*
import scala.util.chaining.scalaUtilChainingOps

private object Utils {

  def sanitizeUri(uri: String): String =
    if (uri.endsWith("/")) sanitizeUri(uri.substring(0, uri.length - 1)) else uri

  def sanitizeUri(uri: java.nio.file.Path): String = sanitizeUri(uri.toUri.toString)

  // define the function that spawns compilation reporter for each module based on the
  // module's hash code TODO: find something more reliable than the hash code
  def getBspLoggedReporterPool(
      originId: String,
      bspIdsByModule: Map[BspModuleApi, BuildTargetIdentifier],
      client: BuildClient
  ): Int => Option[CompileProblemReporter] = { (moduleHashCode: Int) =>
    bspIdsByModule.find(_._1.hashCode == moduleHashCode).map {
      case (module: JavaModuleApi, targetId) =>
        val buildTarget = module.bspBuildTarget
        val taskId = new TaskId(module.hashCode.toString)
        new BspCompileProblemReporter(
          client,
          targetId,
          buildTarget.displayName.getOrElse(targetId.getUri),
          taskId,
          Option(originId)
        )
    }
  }

  // Get the execution status code given the results from Evaluator.evaluate
  def getStatusCode(resultsLists: Seq[ExecutionResultsApi]): StatusCode = {
    val statusCodes =
      resultsLists.flatMap(r =>
        r.transitiveResultsApi.keys.map(task => getStatusCodePerTask(r, task)).toSeq
      )
    if (statusCodes.contains(StatusCode.ERROR)) StatusCode.ERROR
    else if (statusCodes.contains(StatusCode.CANCELLED)) StatusCode.CANCELLED
    else StatusCode.OK
  }

  def makeBuildTarget(
      id: BuildTargetIdentifier,
      depsIds: Seq[BuildTargetIdentifier],
      bt: BspBuildTarget,
      data: Option[(String, Object)]
  ): BuildTarget = {
    val buildTarget = new BuildTarget(
      id,
      bt.tags.asJava,
      bt.languageIds.asJava,
      depsIds.asJava,
      new BuildTargetCapabilities().tap { it =>
        it.setCanCompile(bt.canCompile)
        it.setCanTest(bt.canTest)
        it.setCanRun(bt.canRun)
        it.setCanDebug(bt.canDebug)
      }
    )

    bt.displayName.foreach(buildTarget.setDisplayName)
    bt.baseDirectory.foreach(p => buildTarget.setBaseDirectory(sanitizeUri(p)))

    for ((dataKind, data) <- data) {
      buildTarget.setDataKind(dataKind)
      buildTarget.setData(data)
    }
    buildTarget
  }

  def outputPaths(
      buildTargetBaseDir: os.Path,
      topLevelProjectRoot: os.Path
  ): Seq[OutputPathItem] = {

    def outputPathItem(path: os.Path) =
      // Spec says, a directory must end with a forward slash
      new OutputPathItem(sanitizeUri(path.toNIO) + "/", OutputPathItemKind.DIRECTORY)

    if (topLevelProjectRoot.startsWith(buildTargetBaseDir))
      Seq(
        outputPathItem(topLevelProjectRoot / ".idea"),
        outputPathItem(topLevelProjectRoot / "out"),
        outputPathItem(topLevelProjectRoot / ".bsp"),
        outputPathItem(topLevelProjectRoot / ".bloop")
      )
    else Nil
  }

  private def getStatusCodePerTask(
      results: ExecutionResultsApi,
      task: TaskApi[?]
  ): StatusCode = {
    results.transitiveResultsApi(task) match {
      case Success(_) => StatusCode.OK
      case Skipped => StatusCode.CANCELLED
      case _ => StatusCode.ERROR
    }
  }

}
