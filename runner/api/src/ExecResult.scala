package mill.runner.api

import java.lang.reflect.InvocationTargetException
import scala.language.implicitConversions

/**
 * The result of a task execution.
 *
 * @tparam T The result type of the computed task.
 */
sealed trait ExecResult[+T] {
  def map[V](f: T => V): ExecResult[V]
  def flatMap[V](f: T => ExecResult[V]): ExecResult[V]
  def asSuccess: Option[ExecResult.Success[T]] = None
  def asFailing: Option[ExecResult.Failing[T]] = None
  def get: T = (this: @unchecked) match {
    case ExecResult.Success(v) => v
    case v: ExecResult.Failing[?] => v.throwException

    // no cases for Skipped or Aborted?
  }
}

object ExecResult {
  implicit def create[T](t: => T): ExecResult[T] = {
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
  case class Success[+T](value: T) extends ExecResult[T] {
    def map[V](f: T => V): Success[V] = ExecResult.Success(f(value))
    def flatMap[V](f: T => ExecResult[V]): ExecResult[V] = f(value)
    override def asSuccess: Option[Success[T]] = Some(this)
  }

  /**
   * A task execution was skipped because of failures in its dependencies.
   */
  case object Skipped extends ExecResult[Nothing] {
    def map[V](f: Nothing => V): Skipped.type = this
    def flatMap[V](f: Nothing => ExecResult[V]): Skipped.type = this
  }

  /**
   * A task execution was skipped/aborted because of earlier (maybe unrelated) tasks failed and the evaluation was in fail-fast mode.
   */
  case object Aborted extends ExecResult[Nothing] {
    def map[V](f: Nothing => V): Aborted.type = this
    def flatMap[V](f: Nothing => ExecResult[V]): Aborted.type = this
  }

  /**
   * A failed task execution.
   * @tparam T The result type of the computed task.
   */
  sealed trait Failing[+T] extends ExecResult[T] {
    def map[V](f: T => V): Failing[V]
    def flatMap[V](f: T => ExecResult[V]): Failing[V]
    override def asFailing: Option[ExecResult.Failing[T]] = Some(this)
    def throwException: Nothing = this match {
      case f: ExecResult.Failure[?] => throw new Result.Exception(f.msg)
      case f: ExecResult.Exception => throw f.throwable
    }
  }

  /**
   * An intentional failure, which provides a proper error message as well as an optional result value.
   * @param msg The error message.
   * @param value The optional result value.
   * @tparam T The result type of the computed task.
   */
  case class Failure[T](msg: String) extends Failing[T] {
    def map[V](f: T => V): Failure[V] = ExecResult.Failure(msg)
    def flatMap[V](f: T => ExecResult[V]): Failure[V] = { Failure(msg) }
    override def toString: String = s"Failure($msg)"
  }

  /**
   * An (mostly) unintentionally failed task which the exception that caused the failure.
   * @param throwable The exception that describes or caused the failure.
   * @param outerStack The [[OuterStack]] of the failed task.
   */
  case class Exception(throwable: Throwable, outerStack: OuterStack)
      extends java.lang.Exception(throwable) with Failing[Nothing] {
    def map[V](f: Nothing => V): Exception = this
    def flatMap[V](f: Nothing => ExecResult[V]): Exception = this

    override def toString(): String = {
      var current = List(throwable)
      while (current.head.getCause != null) {
        current = current.head.getCause :: current
      }
      current.reverse
        .flatMap { ex =>
          val elements = ex.getStackTrace.dropRight(outerStack.value.length)
          val formatted =
            // for some reason .map without the explicit ArrayOps conversion doesn't work,
            // and results in `ExecResult[String]` instead of `Array[String]`
            new scala.collection.ArrayOps(elements).map("    " + _)
          Seq(ex.toString) ++ formatted

        }
        .mkString("\n")
    }
  }

  class OuterStack(val value: Seq[StackTraceElement]) {
    def this(value: Array[StackTraceElement]) = this(value.toIndexedSeq)

    override def hashCode(): Int = value.hashCode()

    override def equals(obj: scala.Any): Boolean = obj match {
      case o: OuterStack => value.equals(o.value)
      case _ => false
    }
  }

  def catchWrapException[T](t: => T): Result[T] = {
    try Result.Success(t)
    catch {
      case e: InvocationTargetException =>
        Result.Failure(makeResultException(e.getCause, new java.lang.Exception()).left.get)
      case e: Exception =>
        Result.Failure(makeResultException(e, new java.lang.Exception()).left.get)
    }
  }

  def makeResultException(e: Throwable, base: java.lang.Exception): Left[String, Nothing] = {
    val outerStack = new ExecResult.OuterStack(base.getStackTrace)
    Left(ExecResult.Exception(e, outerStack).toString)
  }
}
