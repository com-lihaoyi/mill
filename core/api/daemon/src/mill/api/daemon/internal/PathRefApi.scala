package mill.api.daemon.internal

trait PathRefApi {
  private[mill] def javaPath: java.nio.file.Path
  def quick: Boolean
  def sig: Int
  def size: Long = -1L // default only for binary backward compatibility
  def count: Int = -1 // default only for binary backward compatibility
  def isDir: Boolean = false // default only for binary backward compatibility
}
