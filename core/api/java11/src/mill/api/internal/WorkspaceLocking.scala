package mill.api.internal

private[mill] object WorkspaceLocking {
  enum LockKind {
    case Read, Write
  }

  trait Lease extends AutoCloseable

  trait DowngradableLease extends Lease {
    def downgradeToRead(): Unit
  }

  trait Manager extends AutoCloseable {
    def runId: String
    def consoleTail: os.Path

    /** Returns a per-run path for well-known out/ artifacts in daemon mode. */
    def artifactPath(default: os.Path): os.Path = default

    /** Publishes this run's well-known artifacts as the latest visible ones under `out/`. */
    def publishArtifacts(): Unit = ()

    def withSelectiveExecutionLock[T](@scala.annotation.unused path: os.Path)(t: => T): T = t
    def metaBuildLock(kind: LockKind): DowngradableLease
    def taskLock(path: os.Path, kind: LockKind): DowngradableLease
  }

  object NoopManager extends Manager {
    override def runId: String = "noop"
    override def consoleTail: os.Path = os.pwd / "out" / "mill-console-tail"
    override def metaBuildLock(lockKind: LockKind): DowngradableLease = new DowngradableLease {
      override def downgradeToRead(): Unit = ()
      override def close(): Unit = ()
    }
    override def taskLock(path: os.Path, kind: LockKind): DowngradableLease = metaBuildLock(kind)
    override def close(): Unit = ()
  }
}
