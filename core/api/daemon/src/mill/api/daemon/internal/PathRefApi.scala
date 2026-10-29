package mill.api.daemon.internal

trait PathRefApi {
  private[mill] def javaPath: java.nio.file.Path
  def quick: Boolean
  @deprecated("Use quickSig and fullSig instead.", "Mill after 1.4.0-M0")
  def sig: Int
  def quickSig: Int = if (quick) sig else 0
  def fullSig: Option[Int] = if (quick) None else Some(sig)
}
