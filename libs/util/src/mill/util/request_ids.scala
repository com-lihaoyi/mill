package mill.util

import java.util.concurrent.atomic.AtomicLong

/** A sequential request id. */
private[mill] case class RequestId(value: Long) extends AnyVal {
  override def toString: String = s"req#$value"
}

/** A thread-safe factory for request ids. */
private[mill] class RequestIdFactory {
  private val counter = AtomicLong(0)

  def next(): RequestId = {
    val value = counter.getAndIncrement()
    RequestId(value)
  }

  override def toString: String = s"RequestIdFactory(current=${counter.get()})"
}
