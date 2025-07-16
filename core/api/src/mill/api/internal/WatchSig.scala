package mill.api.internal
import mill.api.daemon.Watchable
import mill.api.PathRef

private[mill] object WatchSig {

  /** @return true if the watchable did not change. */
  def haveNotChanged(w: Watchable): Boolean = poll(w) == signature(w)

  def poll(w: Watchable): Long = w match {
    case Watchable.Path(p, quick, sig) =>
      new PathRef(os.Path(p), quick, sig, PathRef.Revalidate.Once).recomputeSig()
    case Watchable.Value(f, _, _) =>
      try f()
      catch { case _ => 0 }
  }

  def signature(w: Watchable): Long = w match {
    case Watchable.Path(p, quick, sig) =>
      new PathRef(os.Path(p), quick, sig, PathRef.Revalidate.Once).sig
    case Watchable.Value(_, sig, _) => sig
  }

}
