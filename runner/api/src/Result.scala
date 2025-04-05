package mill.runner.api
import scala.collection.BuildFrom
import collection.mutable

/**
 * Represents a computation that either succeeds with a value [[T]] or
 * fails. Basically equivalent to `Either[String, T]`, with converters
 * back and forther via [[Result.toEither]] or [[Result.fromEither]]
 */
sealed trait Result[+T] {
  def map[V](f: T => V): Result[V]
  def flatMap[V](f: T => Result[V]): Result[V]
  def get: T
  def toOption: Option[T]
  def toEither: Either[String, T]
  def errorOpt: Option[String]
}
object Result {
  implicit def create[T](value: T): Result[T] = Success(value)

  case class Success[+T](value: T) extends Result[T] {

    def map[V](f: T => V): Result[V] = Success(f(value))

    def flatMap[V](f: T => Result[V]): Result[V] = f(value)
    def get = value
    def toOption: Option[T] = Some(value)
    def toEither: Either[String, T] = Right(value)
    def errorOpt: Option[String] = None
  }
  case class Failure(error: String) extends Result[Nothing] {
    def map[V](f: Nothing => V): Result[Nothing] = this

    def flatMap[V](f: Nothing => Result[V]): Result[Nothing] = this
    def get = sys.error(error)
    def toOption: Option[Nothing] = None
    def toEither: Either[String, Nothing] = Left(error)
    def errorOpt: Option[String] = Some(error)
  }

  def fromEither[T](either: Either[String, T]) = either match {
    case Left(err) => Result.Failure(err)
    case Right(value) => Result.Success(value)
  }

  // implementation similar to scala.concurrent.Future#sequence
  def sequence[B, M[X] <: IterableOnce[X]](in: M[Result[B]])(
      implicit cbf: BuildFrom[M[Result[B]], B, M[B]]
  ): Result[M[B]] = {
    in.iterator
      .foldLeft[Result[mutable.Builder[B, M[B]]]](Success(cbf.newBuilder(in))) {
        case (acc, el) =>
          for (a <- acc; e <- el) yield a += e
      }
      .map(_.result())
  }

  class Exception(val error: String) extends java.lang.Exception(error)
}
