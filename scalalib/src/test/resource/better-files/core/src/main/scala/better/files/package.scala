package better

import java.io.{InputStream, StreamTokenizer}
import java.nio.charset.Charset

import scala.collection.mutable
import scala.util.{Failure, Success, Try}

package object files extends Implicits {

  /**
    * Default array buffer size
    * Seems like a good value used by JDK: (see: java.io.BufferedInputStream.DEFAULT_BUFFER_SIZE)
    */
  val defaultBufferSize = 8192

  /**
    * The default charset used by better-files
    * Note: It uses java.net.charset.Charset.defaultCharset() in general but if the default supports byte-order markers,
    *       it uses a more compliant version than the JDK one (see: https://github.com/pathikrit/better-files/issues/107)
    */
  val defaultCharset: Charset =
    UnicodeCharset(Charset.defaultCharset())

  val EOF = StreamTokenizer.TT_EOF

  type Files = Iterator[File]

  /**
    * If bufferSize is set to less than or equal to 0, we don't buffer
    * @param bufferSize
    * @return
    */
  def resourceAsStream(name: String, bufferSize: Int = defaultBufferSize): InputStream =
    currentClassLoader().getResourceAsStream(name).buffered(bufferSize)

  // Some utils:
  private[files] def newMultiMap[A, B]: mutable.MultiMap[A, B] = new mutable.HashMap[A, mutable.Set[B]] with mutable.MultiMap[A, B]

  @inline private[files] def when[A](condition: Boolean)(f: => A): Option[A] = if (condition) Some(f) else None

  @inline private[files] def repeat[U](n: Int)(f: => U): Unit = (1 to n).foreach(_ => f)

  private[files] def currentClassLoader() = Thread.currentThread().getContextClassLoader

  private[files] def eofReader(read: => Int): Iterator[Int] = Iterator.continually(read).takeWhile(_ != EOF)

  /**
    * Utility to apply f on all xs skipping over errors
    * Throws the last error that happened
    * *
    * @param xs
    * @param f
    * @tparam A
    */
  private[files] def tryAll[A](xs: Seq[A])(f: A => Unit): Unit = {
    val res = xs.foldLeft(Option.empty[Throwable]) {
      case (currError, a) =>
        Try(f(a)) match {
          case Success(_) => currError
          case Failure(e) => Some(e)
        }
    }
    res.foreach(throwable => throw throwable)
  }
}
