package mill.internal

/**
 * Small class to take named threads and assign them stable integer IDs
 */
private class ThreadNumberer() {
  private val threadIds = collection.mutable.Map.empty[Thread, Int]

  def getThreadId(thread: Thread): Int = synchronized {
    // start thread IDs from 1 so they're easier to count
    threadIds.getOrElseUpdate(thread, threadIds.size + 1)
  }
}
