package mill.bsp

import ch.epfl.scala.bsp4j.{BuildClient, BuildTargetIdentifier, StatusCode, TaskId}
import mill.api.CompileProblemReporter
import mill.api.Result.{Skipped, Success}
import mill.eval.Evaluator
import mill.scalalib.JavaModule
import mill.scalalib.bsp.BspModule

object Utils {

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
  def getStatusCode(results: Evaluator.Results): StatusCode = {
    val statusCodes = results.results.keys.map(task => getStatusCodePerTask(results, task)).toSeq
    if (statusCodes.contains(StatusCode.ERROR))
      StatusCode.ERROR
    else if (statusCodes.contains(StatusCode.CANCELLED))
      StatusCode.CANCELLED
    else
      StatusCode.OK
  }

  private[this] def getStatusCodePerTask(
      results: Evaluator.Results,
      task: mill.define.Task[_]
  ): StatusCode = {
    results.results(task) match {
      case Success(_) => StatusCode.OK
      case Skipped => StatusCode.CANCELLED
      case _ => StatusCode.ERROR
    }
  }

}
