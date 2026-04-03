package mill.api.daemon.internal

import scala.util.control.{NonFatal => StdNonFatal}
import scala.util.{Failure, Success, Try}

/**
 * Same as [[scala.util.control.NonFatal]], but also considers LinkageError-s to be non fatal
 */
object NonFatal {

  def Try[T](f: => T): Try[T] =
    try Success(f)
    catch {
      case NonFatal(ex) =>
        Failure(ex)
    }

  def apply(t: Throwable): Boolean =
    StdNonFatal(t) || {
      t match {
        case _: LinkageError => true
        case _ => false
      }
    }

  def unapply(t: Throwable): Option[Throwable] =
    if (apply(t)) Some(t) else None
}
