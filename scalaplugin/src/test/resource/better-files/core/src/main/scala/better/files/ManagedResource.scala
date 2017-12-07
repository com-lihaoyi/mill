package better.files

import java.util.concurrent.atomic.AtomicBoolean

import scala.util.Try
import scala.util.control.NonFatal

/**
  * A typeclass to denote a disposable resource
  * @tparam A
  */
trait Disposable[-A]  {
  def dispose(resource: A): Unit

  def disposeSilently(resource: A): Unit = {
    val _ = Try(dispose(resource))
  }
}

object Disposable {
  def apply[A](disposeMethod: A => Any): Disposable[A] = new Disposable[A] {
    override def dispose(resource: A) = {
      val _ = disposeMethod(resource)
    }
  }

  implicit val closableDisposer: Disposable[AutoCloseable] =
    Disposable(_.close())

  val fileDisposer: Disposable[File] =
    Disposable(_.delete(swallowIOExceptions = true))
}

class ManagedResource[A](resource: A)(implicit disposer: Disposable[A]) {
  private[this] val isDisposed = new AtomicBoolean(false)
  private[this] def disposeOnce() = if (!isDisposed.getAndSet(true)) disposer.dispose(resource)

  // This is the Scala equivalent of how javac compiles try-with-resources,
  // Except that fatal exceptions while disposing take precedence over exceptions thrown previously
  private[this] def disposeOnceAndThrow(e1: Throwable) = {
    try {
      disposeOnce()
    } catch {
      case NonFatal(e2) => e1.addSuppressed(e2)
      case e2: Throwable =>
        e2.addSuppressed(e1)
        throw e2
    }
    throw e1
  }

  def foreach[U](f: A => U): Unit = {
    val _ = map(f)
  }

  def map[B](f: A => B): B = {
    try {
      f(resource)
    } catch {
      case e1: Throwable => disposeOnceAndThrow(e1)
    } finally {
      disposeOnce()
    }
  }

  def withFilter(f: A => Boolean): this.type = {
    if (!f(resource)) disposeOnce()
    this
  }

  /**
    * This handles lazy operations (e.g. Iterators)
    * for which resource needs to be disposed only after iteration is done
    *
    * @param f
    * @tparam B
    * @return
    */
  def flatMap[B](f: A => Iterator[B]): Iterator[B] = {
    val it = f(resource)
    it withHasNext {
      try {
        val result = it.hasNext
        if (!result) disposeOnce()
        result
      } catch {
        case e1: Throwable => disposeOnceAndThrow(e1)
      }
    }
  }
}
