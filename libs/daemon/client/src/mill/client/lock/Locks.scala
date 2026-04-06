package mill.client.lock

import mill.constants.DaemonFiles

/** Container for launcher and daemon locks. */
final class Locks(
    /** The lock used by the client process (the launcher that launches the server). */
    val launcherLock: Lock,
    /** The lock used by the server process. */
    val daemonLock: Lock
) extends AutoCloseable {

  override def close(): Unit = {
    launcherLock.delete()
    daemonLock.delete()
  }
}

object Locks {
  @deprecated("Use forDirectory instead", "")
  def files(daemonDir: String): Locks = new Locks(
    new FileLock(s"$daemonDir/${DaemonFiles.launcherLock}"),
    new FileLock(s"$daemonDir/${DaemonFiles.daemonLock}")
  )

  @deprecated("Use forDirectory instead", "")
  def pid(daemonDir: String): Locks = new Locks(
    new PidLock(s"$daemonDir/${DaemonFiles.launcherLock}"),
    new PidLock(s"$daemonDir/${DaemonFiles.daemonLock}")
  )

  def memory(): Locks = new Locks(new MemoryLock(), new MemoryLock())

  def forDirectory(daemonDir: String, useFileLocks: Boolean): Locks =
    if (useFileLocks) files(daemonDir)
    else pid(daemonDir)
}
