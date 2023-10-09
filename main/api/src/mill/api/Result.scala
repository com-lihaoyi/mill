package mill.api

import scala.language.implicitConversions

/**
 * The result of a task execution.
 *
 * @tparam T The result type of the computed task.
 */
sealed trait Result[+T] {
  def map[V](f: T => V): Result[V]
  def flatMap[V](f: T => Result[V]): Result[V]
  def asSuccess: Option[Result.Success[T]] = None
  def asFailing: Option[Result.Failing[T]] = None

}

object Result {
  implicit def create[T](t: => T): Result[T] = {
    try Success(t)
    catch {
      case e: Throwable =>
        Exception(e, new OuterStack(new java.lang.Exception().getStackTrace().toIndexedSeq))
    }
  }

  /**
   * A successful task execution.
   * @param value The value computed by the task.
   * @tparam T The result type of the computed task.
   */
  case class Success[+T](value: T) extends Result[T] {
    def map[V](f: T => V): Success[V] = Result.Success(f(value))
    def flatMap[V](f: T => Result[V]): Result[V] = f(value)
    override def asSuccess: Option[Success[T]] = Some(this)
  }

  /**
   * A task execution was skipped because of failures in it's dependencies.
   */
  case object Skipped extends Result[Nothing] {
    def map[V](f: Nothing => V): Skipped.type = this
    def flatMap[V](f: Nothing => Result[V]): Skipped.type = this
  }

  /**
   * A task execution was skipped/aborted because of earlier (maybe unrelated) tasks failed and the evaluation was in fail-fast mode.
   */
  case object Aborted extends Result[Nothing] {
    def map[V](f: Nothing => V): Aborted.type = this
    def flatMap[V](f: Nothing => Result[V]): Aborted.type = this
  }

  /**
   * A failed task execution.
   * @tparam T The result type of the computed task.
   */
  sealed trait Failing[+T] extends Result[T] {
    def map[V](f: T => V): Failing[V]
    def flatMap[V](f: T => Result[V]): Failing[V]
    override def asFailing: Option[Result.Failing[T]] = Some(this)

  }

  /**
   * An intensional failure, which provides a proper error message as well as an optional result value.
   * @param msg The error message.
   * @param value The optional result value.
   * @tparam T The result type of the computed task.
   */
  case class Failure[T](msg: String, value: Option[T] = None) extends Failing[T] {
    def map[V](f: T => V): Failure[V] = Result.Failure(msg, value.map(f(_)))
    def flatMap[V](f: T => Result[V]): Failure[V] =
      Failure(msg, value.flatMap(f(_).asSuccess.map(_.value)))
  }

  /**
   * An (mostly) unintentionally failed task which the exception that caused the failure.
   * @param throwable The exception that describes or caused the failure.
   * @param outerStack The [[OuterStack]] of the failed task.
   */
  case class Exception(throwable: Throwable, outerStack: OuterStack) extends Failing[Nothing] {
    def map[V](f: Nothing => V): Exception = this
    def flatMap[V](f: Nothing => Result[V]): Exception = this

    override def toString(): String = {
      var current = List(throwable)
      while (current.head.getCause != null) {
        current = current.head.getCause :: current
      }
      current.reverse
        .flatMap(ex =>
          Seq(ex.toString) ++
            ex.getStackTrace.dropRight(outerStack.value.length).map("    " + _)
        )
        .mkString("\n")
    }
  }

  class OuterStack(val value: Seq[StackTraceElement]) {
    def this(value: Array[StackTraceElement]) = this(value.toIndexedSeq)

    override def hashCode(): Int = value.hashCode()

    override def equals(obj: scala.Any) = obj match {
      case o: OuterStack => value.equals(o.value)
      case _ => false
    }
  }
}
