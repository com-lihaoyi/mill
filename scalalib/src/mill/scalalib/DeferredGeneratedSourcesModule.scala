package mill.scalalib

import mill.*
import mill.define.NamedTask

/**
 * A trait holding methods for expressing deferred source generators,
 * which are useful for wiring in source-generation logic the output
 * of which can be predicted without execution.
 *
 * This is useful for preventing IDE-integration (BSP/Idea/Bloop) from
 * failing when the code-generation task fails for one reason or another.
 */
trait DeferredGeneratedSourcesModule extends JavaModule {

  /**
   * A set of tasks producing generated code. The difference between this and
   * [[generatedSources]] lies in the fact that IDE-related tasks
   * (BSP/Bloop/GenIdea) will not run the tasks, but will use them to statically
   * determine the location of the source code they will produce by looking up their
   * [[Task#deferredGeneratedSourceRoots]], knowing that these roots will be present under
   * the dest directories once the task is run.
   */
  def deferredGeneratedSourceTasks: Seq[NamedTask[_]] = Seq.empty

  private def predictGeneratedSourceRoots(task: NamedTask[_])(out: os.Path): Seq[os.Path] = {
    val dest = mill
      .eval
      .EvaluatorPaths
      .resolveDestPaths(out, task)
      .dest
    val explicitSourceRoots = task.deferredGeneratedSourceRoots
    if (explicitSourceRoots.isEmpty) Seq(dest)
    else explicitSourceRoots.map(subPath => dest / subPath)
  }

  /**
   * Computes the list of source roots that will be produced by [[deferredGeneratedSourceTasks]] without
   * actually running the generators in question.
   */
  final def predictedGeneratedSources: T[Seq[PathRef]] = T {
    val out = T.out
    deferredGeneratedSourceTasks.flatMap(t => predictGeneratedSourceRoots(t)(out)).map(PathRef(_))
  }

  /**
   * Runs the lazy source generators, returning references to the expanded generated source roots
   * they are supposed to have written code in.
   */
  final def deferredGeneratedSources: T[Seq[PathRef]] = T {
    val out = T.out
    T.sequence {
      deferredGeneratedSourceTasks.asInstanceOf[Seq[T[Any]]].map { t =>
        t.map((_: Any) => (out: os.Path) => predictGeneratedSourceRoots(t)(out))
      }
    }().flatMap(_.apply(out)).map(PathRef(_))
  }

  /**
   * Task that IDE-configuration tasks should rely on, as they avoid eagerly
   * running source generators referenced by [[deferredGeneratedSourceTasks]]
   */
  def ideSources: T[Seq[PathRef]] =
    Task { sources() ++ generatedSources() ++ predictedGeneratedSources() }

  override def allSources: T[Seq[PathRef]] =
    Task { sources() ++ generatedSources() ++ deferredGeneratedSources() }

}
