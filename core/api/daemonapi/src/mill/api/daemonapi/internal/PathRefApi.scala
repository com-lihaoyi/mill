package mill.api.daemonapi.internal

trait PathRefApi {
  private[mill] def javaPath: java.nio.file.Path
  def quick: Boolean
  def sig: Int
}
