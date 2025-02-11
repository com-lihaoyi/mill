package mill.api

sealed trait Result[+T] {
  def map[V](f: T => V): Result[V]
  def flatMap[V](f: T => Result[V]): Result[V]
  def getOrThrow: T
}
object Result {
  implicit def create[T](value: T): Result[T] = Success(value)
  case class Success[+T](value: T) extends Result[T]{

    def map[V](f: T => V): Result[V] = Success(f(value))

    def flatMap[V](f: T => Result[V]): Result[V] = f(value)
    def getOrThrow = value
  }
  case class Failure(error: String) extends Throwable with Result[Nothing] {
    def map[V](f: Nothing => V): Result[Nothing] = this

    def flatMap[V](f: Nothing => Result[V]): Result[Nothing] = this
    def getOrThrow = sys.error(error)
  }
}
