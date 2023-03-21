package mill.internal


trait Watchable {
  def poll(): Long
}
object Watchable{
  def mtimeIfExists(p: os.Path) = if (os.exists(p)) os.mtime(p) else 0L
  /**
   * Recursively mtimes things, with the sole purpose of providing a number
   * that will change if that file changes or that folder's contents changes
   *
   * Ensure we include the file paths within a folder as part of the folder
   * signature, as file moves often do not update the mtime but we want to
   * trigger a "something changed" event anyway
   */
  def pathSignature(p: os.Path) =
    if (!os.exists(p)) 0L
    else try {
      if (os.isDir(p)) os.walk(p).map(x => x.hashCode + mtimeIfExists(x)).sum
      else os.mtime(p)
    } catch { case e: java.nio.file.NoSuchFileException =>
      0L
    }
  case class Path(p: os.Path) extends Watchable {
    def poll() = pathSignature(p)
  }
  case class Value(f: () => Long) extends Watchable {
    def poll() = f()
  }
}
