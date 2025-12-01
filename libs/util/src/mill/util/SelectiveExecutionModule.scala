package mill.util

import mill.api.Result
import mill.constants.OutFiles.OutFiles
import mill.*
import mill.api.Evaluator
import mill.api.SelectMode

/**
 * Mill Module to support selective test execution in large projects.
 *
 * Read more about it at: https://mill-build.org/mill/large/selective-execution.html
 */
trait SelectiveExecutionModule extends mill.api.Module {

  /**
   * Run to store a baseline snapshot of the Mill task inputs or implementations
   * necessary to run [[tasks]], to be later compared against metadata computed
   * after a code change to determine which tasks were affected and need to be re-run
   */
  def prepare(evaluator: Evaluator, tasks: String*): Command[Unit] =
    Task.Command(exclusive = true) {
      evaluator.resolveTasks(
        if (tasks.isEmpty) Seq("__") else tasks,
        SelectMode.Multi
      ).map { resolvedTasks =>
        val computed = evaluator.selective.computeMetadata(resolvedTasks)

        evaluator.selective.saveMetadata(computed.metadata)
      }
    }

  /**
   * Run after [[prepare]], prints out the tasks in [[tasks]] that are affected by
   * any changes to the task inputs or task implementations since [[prepare]]
   * was run. Effectively a dry-run version of [[run]] that lets you show the tasks
   * that would be run without actually running them
   */
  def resolve(evaluator: Evaluator, tasks: String*): Command[Array[String]] =
    Task.Command(exclusive = true) {
      evaluator.selective.resolve0(tasks).map { success =>
        success.foreach(println)
        success
      }
    }

  /**
   * Similar to [[resolve]], but prints the output as a JSON tree so you can see
   * the chain of dependencies that caused each selectively resolved task to be
   * resolved from some upstream changed input
   */
  def resolveTree(evaluator: Evaluator, tasks: String*): Command[ujson.Value] =
    Task.Command(exclusive = true) {
      evaluator.selective.resolveTree(tasks).map { success =>
        println(success.render(indent = 2))
        success
      }
    }

  /**
   * Similar to [[resolve]], but prints the _changed upstream tasks_ rather than
   * the _selected downstream tasks_.
   */
  def resolveChanged(evaluator: Evaluator, tasks: String*): Command[Seq[String]] =
    Task.Command(exclusive = true) {
      evaluator.selective.resolveChanged(tasks).map { success =>
        success.foreach(println)
        success
      }
    }

  /**
   * Run after [[prepare]], selectively executes the tasks in [[tasks]] that are
   * affected by any changes to the task inputs or task implementations since [[prepare]]
   * was run
   */
  def run(evaluator: Evaluator, tasks: String*): Command[Unit] =
    Task.Command(exclusive = true) {
      if (!os.exists(evaluator.outPath / OutFiles.millSelectiveExecution)) {
        Result.Failure("`selective.run` can only be run after `selective.prepare`")
      } else {
        evaluator.selective.resolve0(tasks).flatMap { resolved =>
          if (resolved.isEmpty) Result.Success(())
          else evaluator.evaluate(resolved.toSeq, SelectMode.Multi).flatMap {
            case Evaluator.Result(_, f: Result.Failure, _, _) => f
            case Evaluator.Result(_, Result.Success(_), _, _) =>
          }
        }
      }
    }
}
