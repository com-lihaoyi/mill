package mill.eval

/**
 * Small class to take named threads and assign them stable integer IDs
 */
class ThreadNumberer() {
  private val threadIds = collection.mutable.Map.empty[Thread, Int]

  def getThreadId(thread: Thread) = synchronized {
    threadIds.getOrElseUpdate(thread, threadIds.size)
  }
}
