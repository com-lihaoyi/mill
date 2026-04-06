package mill.client.lock

/** Combines two locks into one, making sure we only lock if we can get both locks. */
class DoubleLock(lock1: Lock, lock2: Lock) extends Lock {

  override def toString: String = s"DoubleLock{lock1=$lock1, lock2=$lock2}"

  override def lock(): Locked = {
    var l1: Locked = null
    var l2: Locked = null
    var result: Locked = null
    try {
      l1 = lock1.lock()
      l2 = lock2.lock()
      result = new DoubleLocked(l1, l2)
    } finally {
      if (result == null) {
        if (l2 != null) l2.release()
        if (l1 != null) l1.release()
      }
    }
    result
  }

  override def tryLock(): TryLocked = {
    var l1: TryLocked = null
    var l2: TryLocked = null
    var result: TryLocked = null
    try {
      l1 = lock1.tryLock()
      if (l1.isLocked) {
        l2 = lock2.tryLock()
        if (l2.isLocked) result = new DoubleTryLocked(l1, l2)
      }
    } finally {
      if (result == null) {
        if (l2 != null) l2.release()
        if (l1 != null) l1.release()
      }
    }
    if (result == null) result = new DoubleTryLocked(null, null)
    result
  }

  override def probe(): Boolean = {
    val tl = tryLock()
    if (!tl.isLocked) true
    else {
      tl.release()
      false
    }
  }

  override def close(): Unit = {
    // Unlock the locks in the opposite order in which we originally took them
    lock2.close()
    lock1.close()
  }

  override def delete(): Unit = close()
}

private[lock] class DoubleLocked(lock1: Locked, lock2: Locked) extends Locked {
  override def release(): Unit = {
    lock2.release()
    lock1.release()
  }
}

private[lock] class DoubleTryLocked(lock1: TryLocked, lock2: TryLocked)
    extends DoubleLocked(lock1, lock2) with TryLocked {
  override def isLocked: Boolean = lock1 != null && lock2 != null
  override def release(): Unit = if (isLocked) super.release()
}
