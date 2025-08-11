package mill.api.daemon

import scala.collection.Factory
import scala.util.boundary

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
  implicit def create[T](value: T): Result[T] =
    try Success(value)
    catch {
      case e: Result.Exception => Result.Failure(e.error)
    }

  final case class Success[+T](value: T) extends Result[T] {

    def map[V](f: T => V): Result[V] = Success(f(value))

    def flatMap[V](f: T => Result[V]): Result[V] = f(value)
    def get = value
    def toOption: Option[T] = Some(value)
    def toEither: Either[String, T] = Right(value)
    def errorOpt: Option[String] = None
  }
  final case class Failure(error: String) extends Result[Nothing] {
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

  extension [A](rr: Result[Result[A]]) {
    def flatten: Result[A] = rr.flatMap(identity)
  }

  /**
   * Converts a `Collection[Result[T]]` into a `Result[Collection[T]]`
   */
  def sequence[B, M[X] <: IterableOnce[X]](in: M[Result[B]])(using
      factory: Factory[B, M[B]]
  ): Result[M[B]] = {
    boundary {
      val builder = factory.newBuilder
      builder.sizeHint(in)
      in.iterator.foreach {
        case Success(b) => builder += b
        case Failure(error) => boundary.break(Failure(error))
      }

      builder.result()
    }
  }

  /**
   * Converts a `Collection[T]` into a `Result[Collection[V]]` using the given `f: T => Result[V]`
   */
  def traverse[A, B, Collection[x] <: IterableOnce[x]](collection: Collection[Result[A]])(
      f: A => Result[B]
  )(using factory: Factory[B, Collection[B]]): Result[Collection[B]] = {
    boundary {
      val builder = factory.newBuilder
      builder.sizeHint(collection)
      collection.iterator.map(_.flatMap(f)).foreach {
        case Success(b) => builder += b
        case Failure(error) => boundary.break(Failure(error))
      }

      builder.result()
    }
  }

  final class Exception(val error: String) extends java.lang.Exception(error)
}
