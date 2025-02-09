package mill.main

import mill.api.Result
import mill.define.{Command, Task}
import mill.eval.{Evaluator, SelectiveExecution}
import mill.main.client.OutFiles
import mill.resolve.{Resolve, SelectMode}

trait SelectiveExecutionModule extends mill.define.Module {

  /**
   * Run to store a baseline snapshot of the Mill task inputs or implementations
   * necessary to run [[tasks]], to be later compared against metadata computed
   * after a code change to determine which tasks were affected and need to be re-run
   */
  def prepare(evaluator: Evaluator, tasks: String*): Command[Unit] =
    Task.Command(exclusive = true) {
      val res: Either[String, Unit] = evaluator.resolveTasks(
        if (tasks.isEmpty) Seq("__") else tasks,
        SelectMode.Multi
      ).map { resolvedTasks =>
        SelectiveExecution.Metadata
          .compute(evaluator, resolvedTasks)
          .map(x => SelectiveExecution.saveMetadata(evaluator, x._1))
      }

      res match {
        case Left(err) => Result.Failure(err)
        case Right(res) => Result.Success(())
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
      SelectiveExecution.resolve0(evaluator, tasks) match {
        case Left(err) => Result.Failure(err)
        case Right(success) =>
          success.foreach(println)
          Result.Success(success)
      }
    }

  /**
   * Similar to [[resolve]], but prints the output as a JSON tree so you can see
   * the chain of dependencies that caused each selectively resolved task to be
   * resolved from some upstream changed input
   */
  def resolveTree(evaluator: Evaluator, tasks: String*): Command[ujson.Value] =
    Task.Command(exclusive = true) {
      SelectiveExecution.resolveTree(evaluator, tasks) match {
        case Left(err) => Result.Failure(err)
        case Right(success) =>
          println(success.render(indent = 2))
          Result.Success(success)
      }
    }

  /**
   * Similar to [[resolve]], but prints the _changed upstream tasks_ rather than
   * the _selected downstream tasks_.
   */
  def resolveChanged(evaluator: Evaluator, tasks: String*): Command[Seq[String]] =
    Task.Command(exclusive = true) {
      SelectiveExecution.resolveChanged(evaluator, tasks) match {
        case Left(err) => Result.Failure(err)
        case Right(success) =>
          success.foreach(println)
          Result.Success(success)
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
        SelectiveExecution.resolve0(evaluator, tasks).flatMap { resolved =>
          if (resolved.isEmpty) Right((Nil, Right(Nil)))
          else evaluator.evaluateTasksNamed(resolved.toSeq, SelectMode.Multi)
        } match {
          case Left(err) => Result.Failure(err)
          case Right((watched, Left(err))) => Result.Failure(err)
          case Right((watched, Right(res))) => Result.Success(())
        }
      }
    }
}
