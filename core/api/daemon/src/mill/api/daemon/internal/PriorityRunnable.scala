package mill.api.daemon.internal

import java.util.concurrent.atomic.AtomicLong

/**
 * Runnable wrapper used by daemon-level priority queues.
 *
 * This lives in `mill.api.daemon.internal` so it is loaded from the shared daemon API
 * classloader rather than from refreshable build/evaluator classloaders.
 */
private[mill] class PriorityRunnable(val priority: Int, run0: () => Unit)
    extends Runnable
    with Comparable[PriorityRunnable] {
  def run(): Unit = run0()

  val priorityRunnableIndex: Long = PriorityRunnable.priorityRunnableCount.getAndIncrement()

  override def compareTo(o: PriorityRunnable): Int = priority.compareTo(o.priority) match {
    case 0 =>
      // `Comparable` wants a total ordering. This index is assigned when a task
      // is submitted, so equal-priority work runs in submission order.
      assert(this == o || this.priorityRunnableIndex != o.priorityRunnableIndex)
      this.priorityRunnableIndex.compareTo(o.priorityRunnableIndex)
    case n => n
  }
}

private object PriorityRunnable {
  private val priorityRunnableCount = new AtomicLong()
}
