package mill.internal

/**
 * Assigns each thread a small stable integer id, used as the chrome-profile
 * track ("tid") for the spans that run on it.
 *
 * A live thread always maps to the same id, so every span it runs over its
 * lifetime shares one readable track. When the execution pool scales back down
 * after `blocking{...}` compensation (see
 * [[mill.exec.ExecutionContexts.ThreadPool]]) and an idle worker is reaped, its
 * id is reclaimed and handed to the next freshly-spawned worker. So the id
 * (track) count tracks the live-thread high-water mark rather than the total
 * number of threads ever created: a killed-then-respawned worker transfers the
 * dead worker's id instead of minting a new one, keeping churned profiles
 * compact without scattering a reused thread's work across multiple tracks.
 *
 * Reclaiming a dead thread's id can never produce overlapping spans on a track:
 * a worker is only reaped while idle (no open span), so its track is already
 * begin/end balanced before the id is reused.
 */
private class ThreadNumberer() {
  private val threadIds = collection.mutable.Map.empty[Thread, Int]
  // Ids of threads that have since died, available for reuse. Smallest-first
  // keeps the assigned ids compact.
  private val freeIds = collection.mutable.TreeSet.empty[Int]
  private var nextId = 1

  def getThreadId(thread: Thread): Int = synchronized {
    threadIds.get(thread) match {
      // A thread we've already numbered keeps its id for its whole lifetime.
      case Some(id) => id
      case None =>
        // First time we see this thread: reclaim the ids of any previously-seen
        // threads that have since died (e.g. reaped pool workers) so this thread
        // can transfer one of them rather than growing the track count.
        val dead = threadIds.iterator.collect { case (t, id) if !t.isAlive => (t, id) }.toList
        for ((t, id) <- dead) {
          threadIds.remove(t)
          freeIds.add(id)
        }

        val id =
          if (freeIds.nonEmpty) {
            val reused = freeIds.head
            freeIds.remove(reused)
            reused
          } else {
            // start thread IDs from 1 so they're easier to count
            val fresh = nextId
            nextId += 1
            fresh
          }
        threadIds(thread) = id
        id
    }
  }
}
