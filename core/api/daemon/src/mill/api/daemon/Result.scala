package mill.api.daemon

import scala.collection.Factory

/**
 * Represents a computation that either succeeds with a value [[T]] or
 * fails. Basically equivalent to `Either[String, T]`, with converters
 * back and forth via [[Result.toEither]] or [[Result.fromEither]]
 */
sealed trait Result[+T] {
  def map[V](f: T => V): Result[V]
  def flatMap[V](f: T => Result[V]): Result[V]
  def get: T
  def toOption: Option[T]
  def toEither: Either[String, T]
  def errorOpt: Option[String]

  def zip[V](rhs: Result[V]): Result[(T, V)] = (this, rhs) match {
    case (Result.Success(l), Result.Success(r)) => Result.Success((l, r))
    case (f: Result.Failure, Result.Success(_)) => f
    case (Result.Success(_), f: Result.Failure) => f
    case (f1: Result.Failure, f2: Result.Failure) => Result.Failure.join(Seq(f1, f2))
  }
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

  /**
   * Models the kind of error reporting supported by Mill's terminal UI. Apart from the simple
   * `error: String`, also supports file position and exception stack trace metadata to
   * provide richer error diagnostics, and can be chained together via `Failure.join`
   * to return multiple failures at once.
   */
  final case class Failure(
      error: String,
      @com.lihaoyi.unroll path: java.nio.file.Path = null,
      index: Int = -1,
      exception: Seq[Failure.ExceptionInfo] = Nil,
      tickerPrefix: String = "",
      next: Option[Failure] = None
  ) extends Result[Nothing] {
    def map[V](f: Nothing => V): Result[Nothing] = this

    def flatMap[V](f: Nothing => Result[V]): Result[Nothing] = this
    def get = sys.error(error)
    def toOption: Option[Nothing] = None
    def toEither: Either[String, Nothing] = Left(error)
    def errorOpt: Option[String] = Some(error)
  }

  object Failure {
    case class ExceptionInfo(clsName: String, msg: String, stack: Seq[StackTraceElement])
    def split(f: Failure) = Iterator.unfold(Option(f))(_.map(t => t.copy(next = None) -> t.next))
    def join(failures: Seq[Failure]): Failure = {
      val flattened: Seq[Failure] = failures.flatMap(split)
      flattened
        .foldLeft(Option.empty[Failure])((f0, f) => Some(f.copy(next = f0)))
        .getOrElse(sys.error("Failure.join cannot take an empty Seq"))
    }
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
    val (failures, successes) = in.iterator.toSeq.partitionMap{
      case Success(b) => Right(b)
      case f: Failure => Left(f)
    }

    if (failures.nonEmpty) Failure.join(failures)
    else Result.Success(factory.fromSpecific(successes))
  }

  /**
   * Converts a `Collection[T]` into a `Result[Collection[V]]` using the given `f: T => Result[V]`
   */
  def traverse[A, B, Collection[x] <: IterableOnce[x]](collection: Collection[Result[A]])(
      f: A => Result[B]
  )(using factory: Factory[B, Collection[B]]): Result[Collection[B]] = {
    sequence[B, Seq](collection.iterator.map(_.flatMap(f)).toSeq).map(factory.fromSpecific)
  }

  final class Exception(val error: String) extends java.lang.Exception(error)
}
