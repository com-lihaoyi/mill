package mill.main

import mill.api.Result
import mill.define.{Command, Task}
import mill.eval.Evaluator
import mill.main.client.OutFiles
import mill.resolve.{Resolve, SelectMode}
import mill.resolve.SelectMode.Separated

trait SelectiveExecutionModule extends mill.define.Module {

  /**
   * Run to store a baseline snapshot of the Mill task inputs or implementations
   * necessary to run [[tasks]], to be later compared against metadata computed
   * after a code change to determine which tasks were affected and need to be re-run
   */
  def prepare(evaluator: Evaluator, tasks: String*): Command[Unit] =
    Task.Command(exclusive = true) {
      val res: Either[String, Unit] = SelectiveExecution.Metadata
        .compute(evaluator, if (tasks.isEmpty) Seq("__") else tasks)
        .map(SelectiveExecution.saveMetadata(evaluator, _))

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
      val result = for {
        resolved <- Resolve.Tasks.resolve(evaluator.rootModule, tasks, SelectMode.Multi)
        diffed <- SelectiveExecution.diffMetadata(evaluator, tasks)
        resolvedDiffed <- {
          if (diffed.isEmpty) Right(Nil)
          else Resolve.Segments.resolve(
            evaluator.rootModule,
            diffed.toSeq,
            SelectMode.Multi,
            evaluator.allowPositionalCommandArgs
          )
        }
      } yield {
        resolved.map(
          _.ctx.segments.render
        ).toSet.intersect(resolvedDiffed.map(_.render).toSet).toArray.sorted
      }

      result match {
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
        RunScript.evaluateTasksNamed(
          evaluator,
          tasks,
          Separated,
          selectiveExecution = true
        ) match {
          case Left(err) => Result.Failure(err)
          case Right((watched, Left(err))) => Result.Failure(err)
          case Right((watched, Right(res))) => Result.Success(res)
        }
      }
    }
}
