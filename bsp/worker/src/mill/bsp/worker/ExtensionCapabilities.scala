package mill.bsp.worker

class ExtensionCapabilities private (
    val languages: Seq[String]
)
object ExtensionCapabilities {
  def apply(languages: Seq[String]): ExtensionCapabilities =
    new ExtensionCapabilities(
      languages = languages
    )
}
