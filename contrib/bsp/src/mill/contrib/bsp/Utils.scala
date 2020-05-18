package mill.contrib.bsp

import ch.epfl.scala.bsp4j._
import mill._
import mill.api.Result.{Skipped, Success}
import mill.api.{BuildProblemReporter, Result}
import mill.contrib.bsp.ModuleUtils._
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
      params: Parameters,
      taskStartMessage: String => String,
      taskStartDataKind: String,
      taskStartData: BuildTargetIdentifier => Object,
      modules: Seq[JavaModule],
      evaluator: Evaluator,
      client: BuildClient
  ): Int => Option[BuildProblemReporter] = { hashCode: Int =>
    getTarget(hashCode, modules, evaluator).map { target =>
      val taskId = new TaskId(getModule(target.getId, modules).compile.hashCode.toString)
      val taskStartParams = new TaskStartParams(taskId)
      taskStartParams.setEventTime(System.currentTimeMillis())
      taskStartParams.setData(taskStartData(target.getId))
      taskStartParams.setDataKind(taskStartDataKind)
      taskStartParams.setMessage(taskStartMessage(target.getDisplayName))
      client.onBuildTaskStart(taskStartParams)
      new BspLoggedReporter(client, target.getId, taskId, params.getOriginId)
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

  private[this] def getStatusCodePerTask(results: Evaluator.Results, task: mill.define.Task[_]): StatusCode = {
    results.results(task) match {
      case _: Success[_] => StatusCode.OK
      case Skipped => StatusCode.CANCELLED
      case _ => StatusCode.ERROR
    }
  }

  // Detect and return the test classes contained in the given TestModule
  def getTestClasses(module: TestModule, evaluator: Evaluator)(implicit ctx: Ctx.Home): Seq[String] = {
    val runClasspath = getTaskResult(evaluator, module.runClasspath)
    val frameworks = getTaskResult(evaluator, module.testFrameworks)
    val compilationResult = getTaskResult(evaluator, module.compile)

    (runClasspath, frameworks, compilationResult) match {
      case (Result.Success(classpath), Result.Success(testFrameworks), Result.Success(compResult)) =>
        val classFingerprint = Jvm.inprocess(
          classpath.asInstanceOf[Seq[PathRef]].map(_.path),
          classLoaderOverrideSbtTesting = true,
          isolated = true,
          closeContextClassLoaderWhenDone = false,
          cl => {
            val fs = TestRunner.frameworks(testFrameworks.asInstanceOf[Seq[String]])(cl)
            fs.flatMap(framework =>
              discoverTests(cl, framework, Agg(compResult.asInstanceOf[CompilationResult].classes.path))
            )
          }
        )
        classFingerprint.map(classF => classF._1.getName.stripSuffix("$"))
      case _ => Seq.empty[String] //TODO: or send notification that something went wrong
    }
  }
}
