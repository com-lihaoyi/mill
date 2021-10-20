package mill.bsp

import ch.epfl.scala.bsp4j._
import mill._
import mill.api.Result.{Skipped, Success}
import mill.api.{BuildProblemReporter, Result}
import mill.bsp.ModuleUtils._
import mill.eval.Evaluator
import mill.modules.Jvm
import mill.scalalib.Lib.discoverTests
import mill.scalalib._
import mill.scalalib.api.CompilationResult
import mill.util.Ctx

object Utils {

  // define the function that spawns compilation reporter for each module based on the
  // module's hash code TODO: find something more reliable than the hash code
  def getBspLoggedReporterPool(
      originId: String,
      modules: Seq[JavaModule],
      evaluator: Evaluator,
      client: BuildClient
  ): Int => Option[BuildProblemReporter] = { hashCode: Int =>
    getTarget(hashCode, modules, evaluator).map { target =>
      val taskId = new TaskId(getModule(target.getId, modules).compile.hashCode.toString)
      val taskStartParams = new TaskStartParams(taskId)
      taskStartParams.setEventTime(System.currentTimeMillis())
      taskStartParams.setData(new CompileTask(target.getId))
      taskStartParams.setDataKind(TaskDataKind.COMPILE_TASK)
      taskStartParams.setMessage(s"Compiling target ${target.getDisplayName}")
      client.onBuildTaskStart(taskStartParams)
      new BspLoggedReporter(client, target, taskId, Option(originId))
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
