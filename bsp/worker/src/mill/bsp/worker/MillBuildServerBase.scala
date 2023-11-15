package mill.bsp.worker

import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import mill.define.Task
import mill.eval.Evaluator
import mill.scalalib.bsp.BspModule

import java.util.concurrent.CompletableFuture
import scala.reflect.ClassTag

trait MillBuildServerBase {
  def debug(msg: String): Unit

  /**
   * Given a function that take input of type T and return output of type V,
   * apply the function on the given inputs and return a completable future of
   * the result. If the execution of the function raises an Exception, complete
   * the future exceptionally. Also complete exceptionally if the server was not
   * yet initialized.
   */
  protected def completable[V](
      hint: String,
      checkInitialized: Boolean = true
  )(f: State => V): CompletableFuture[V]

  def completableTasks[T, V, W: ClassTag](
      hint: String,
      targetIds: State => Seq[BuildTargetIdentifier],
      tasks: BspModule => Task[W]
  )(f: (Evaluator, State, BuildTargetIdentifier, BspModule, W) => T)(agg: java.util.List[T] => V)
      : CompletableFuture[V]

  def enableSemanticDb: Boolean
}
