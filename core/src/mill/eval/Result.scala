package mill.eval

sealed trait Result[+T]{
  def map[V](f: T => V): Result[V] = this match{
    case Result.Success(v) => Result.Success(f(v))
    case f: Result.Failing => f
    case Result.Skipped => Result.Skipped
  }
}
object Result{
  implicit def create[T](t: => T): Result[T] = {
    try Success(t)
    catch { case e: Throwable => Exception(e, new java.lang.Exception().getStackTrace) }
  }
  case class Success[T](value: T) extends Result[T]
  case object Skipped extends Result[Nothing]
  sealed trait Failing extends Result[Nothing]
  case class Failure(msg: String) extends Failing
  case class Exception(throwable: Throwable, outerStack: Seq[StackTraceElement]) extends Failing
}