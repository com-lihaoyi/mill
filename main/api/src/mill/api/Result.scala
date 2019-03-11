package mill.api

/**
 * The result of a task execution.
 * @tparam T The result type of the computed task.
 */
sealed trait Result[+T] {
  def map[V](f: T => V): Result[V]
  def asSuccess: Option[Result.Success[T]] = None
}

object Result {
  implicit def create[T](t: => T): Result[T] = {
    try Success(t)
    catch { case e: Throwable => Exception(e, new OuterStack(new java.lang.Exception().getStackTrace)) }
  }

  /**
   * A successful task execution.
   * @param value The value computed by the task.
   * @tparam T The result type of the computed task.
   */
  case class Success[+T](value: T) extends Result[T] {
    def map[V](f: T => V) = Result.Success(f(value))
    override def asSuccess = Some(this)
  }

  /**
   * A task execution was skipped because of failures in it's dependencies.
   */
  case object Skipped extends Result[Nothing] {
    def map[V](f: Nothing => V) = this
  }

  /**
   * A failed task execution.
   * @tparam T The result type of the computed task.
   */
  sealed trait Failing[+T] extends Result[T] {
    def map[V](f: T => V): Failing[V]
  }

  /**
   * An intensional failure, which provides a proper error message as well as an optional result value.
   * @param msg The error message.
   * @param value The optional result value.
   * @tparam T The result type of the computed task.
   */
  case class Failure[T](msg: String, value: Option[T] = None) extends Failing[T] {
    def map[V](f: T => V) = Result.Failure(msg, value.map(f(_)))
  }

  /**
   * An (mostly) unintentionally failed task which the exception that caused the failure.
   * @param throwable The exception that describes or caused the failure.
   * @param outerStack The [[OuterStack]] of the failed task.
   */
  case class Exception(throwable: Throwable, outerStack: OuterStack) extends Failing[Nothing] {
    def map[V](f: Nothing => V) = this
  }

  class OuterStack(val value: Seq[StackTraceElement]) {
    override def hashCode() = value.hashCode()

    override def equals(obj: scala.Any) = obj match {
      case o: OuterStack => value.equals(o.value)
      case _ => false
    }
  }
}