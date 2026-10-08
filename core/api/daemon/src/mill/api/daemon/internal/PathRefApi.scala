package mill.api.daemon.internal

trait PathRefApi {
  private[mill] def javaPath: java.nio.file.Path
  def quick: Boolean
  def sig: Int
  def size: Long
  def count: Int
  def isDir: Boolean
}
