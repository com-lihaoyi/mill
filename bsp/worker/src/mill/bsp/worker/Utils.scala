package mill.bsp.worker

import ch.epfl.scala.bsp4j.{BuildClient, BuildTarget, BuildTargetCapabilities, BuildTargetIdentifier, OutputPathItem, OutputPathItemKind, StatusCode, TaskId}
import mill.api.{CompileProblemReporter, PathRef}
import mill.api.Result.{Skipped, Success}
import mill.eval.Evaluator
import mill.scalalib.JavaModule
import mill.scalalib.bsp.{BspBuildTarget, BspModule}
import os.up

import scala.jdk.CollectionConverters._
import scala.util.chaining.scalaUtilChainingOps

private object Utils {

  def sanitizeUri(uri: String): String =
    if (uri.endsWith("/")) sanitizeUri(uri.substring(0, uri.length - 1)) else uri

  def sanitizeUri(uri: os.Path): String = sanitizeUri(uri.toNIO.toUri.toString)

  def sanitizeUri(uri: PathRef): String = sanitizeUri(uri.path)

  // define the function that spawns compilation reporter for each module based on the
  // module's hash code TODO: find something more reliable than the hash code
  def getBspLoggedReporterPool(
      originId: String,
      bspIdsByModule: Map[BspModule, BuildTargetIdentifier],
      client: BuildClient
  ): Int => Option[CompileProblemReporter] = { moduleHashCode: Int =>
    bspIdsByModule.find(_._1.hashCode == moduleHashCode).map {
      case (module: JavaModule, targetId) =>
        val buildTarget = module.bspBuildTarget
        val taskId = new TaskId(module.compile.hashCode.toString)
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
  def getStatusCode(resultsLists: Seq[Evaluator.Results]): StatusCode = {
    val statusCodes =
      resultsLists.flatMap(r => r.results.keys.map(task => getStatusCodePerTask(r, task)).toSeq)
    if (statusCodes.contains(StatusCode.ERROR)) StatusCode.ERROR
    else if (statusCodes.contains(StatusCode.CANCELLED)) StatusCode.CANCELLED
    else StatusCode.OK
  }

  def makeBuildTarget(id: BuildTargetIdentifier, depsIds: Seq[BuildTargetIdentifier], bt: BspBuildTarget,data:Option[(String,Object)]) = {
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
    bt.baseDirectory.foreach(p =>buildTarget.setBaseDirectory(sanitizeUri(p)))

    for ((dataKind, data) <- data) {
      buildTarget.setDataKind(dataKind)
      buildTarget.setData(data)
    }
    buildTarget
  }

  def outputPaths(outDir: os.Path, buildTargetBaseDir: os.Path,topLevelProjectRoot:os.Path) = {
    val output = new OutputPathItem(
      // Spec says, a directory must end with a forward slash
      sanitizeUri(outDir) + "/",
      OutputPathItemKind.DIRECTORY
    )

    def ignore(path: os.Path): Boolean = {
      path.last.startsWith(".") ||
        path.endsWith(os.RelPath("out")) ||
        path.endsWith(os.RelPath("target")) ||
        path.endsWith(os.RelPath("docs")) ||
        path.endsWith(os.RelPath("mill-build"))
    }

    def projectsRootPaths = os.walk(topLevelProjectRoot, ignore).collect {
      case p if p.endsWith(os.RelPath("build.sc")) => p / up
    }
    def outputPathItem(path:os.Path) =
      new OutputPathItem(
        // Spec says, a directory must end with a forward slash
        sanitizeUri(path) + "/",
        OutputPathItemKind.DIRECTORY
      )
    def additionalExclusions = projectsRootPaths.flatMap { path =>
      Seq(
        outputPathItem(path / ".idea"),
        outputPathItem(path / "out"),
        outputPathItem(path / ".bsp"),
        outputPathItem(path / ".bloop"),
        )
    }
    output +: (if (topLevelProjectRoot.startsWith(buildTargetBaseDir)) {
      additionalExclusions
    } else Nil)
  }
  private[this] def getStatusCodePerTask(
      results: Evaluator.Results,
      task: mill.define.Task[_]
  ): StatusCode = {
    results.results(task).result match {
      case Success(_) => StatusCode.OK
      case Skipped => StatusCode.CANCELLED
      case _ => StatusCode.ERROR
    }
  }

}
