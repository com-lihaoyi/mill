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
    catch { case e: Throwable => Exception(e, new OuterStack(new java.lang.Exception().getStackTrace)) }
  }
  case class Success[T](value: T) extends Result[T]
  case object Skipped extends Result[Nothing]
  sealed trait Failing extends Result[Nothing]
  case class Failure(msg: String) extends Failing
  case class Exception(throwable: Throwable, outerStack: OuterStack) extends Failing
  class OuterStack(val value: Seq[StackTraceElement]){
    override def hashCode() = value.hashCode()

    override def equals(obj: scala.Any) = obj match{
      case o: OuterStack => value.equals(o.value)
      case _ => false
    }
  }
}