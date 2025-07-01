package mill.api.shared.internal

trait PathRefApi {
  private[mill] def javaPath: java.nio.file.Path
  def quick: Boolean
  def sig: Int
}
