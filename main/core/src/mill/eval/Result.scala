package mill.eval

sealed trait Result[+T]{
  def map[V](f: T => V): Result[V]
  def asSuccess: Option[Result.Success[T]] = None
}
object Result{
  implicit def create[T](t: => T): Result[T] = {
    try Success(t)
    catch { case e: Throwable => Exception(e, new OuterStack(new java.lang.Exception().getStackTrace)) }
  }
  case class Success[+T](value: T) extends Result[T]{
    def map[V](f: T => V) = Result.Success(f(value))
    override def asSuccess = Some(this)
  }
  case object Skipped extends Result[Nothing]{
    def map[V](f: Nothing => V) = this
  }
  sealed trait Failing[+T] extends Result[T]{
    def map[V](f: T => V): Failing[V]
  }
  case class Failure[T](msg: String, value: Option[T] = None) extends Failing[T]{
    def map[V](f: T => V) = Result.Failure(msg, value.map(f(_)))
  }
  case class Exception(throwable: Throwable, outerStack: OuterStack) extends Failing[Nothing]{
    def map[V](f: Nothing => V) = this
  }
  class OuterStack(val value: Seq[StackTraceElement]){
    override def hashCode() = value.hashCode()

    override def equals(obj: scala.Any) = obj match{
      case o: OuterStack => value.equals(o.value)
      case _ => false
    }
  }
}