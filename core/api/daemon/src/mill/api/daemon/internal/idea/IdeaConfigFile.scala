package mill.api.daemon.internal.idea

/**
 * An Idea config file contribution
 *
 * @param subPath   The sub-path of the config file, relative to the Idea config directory (`.idea`)
 * @param component The Idea component
 * @param config    The actual (XML) configuration, encoded as [[Element]]s
 *
 *                  Note: the `name` fields is deprecated in favour of `subPath`, but kept for backward compatibility.
 */
final case class IdeaConfigFile(
    subPath: java.nio.file.Path,
    component: Option[String],
    config: Seq[Element]
) {
  // An empty component name meas we contribute a whole file
  // If we have a full file, we only accept a single root xml node.
  require(
    component.forall(_.nonEmpty) && (component.nonEmpty || config.size == 1),
    "Files contributions must have exactly one root element."
  )

  def asWholeFile: Option[(java.nio.file.Path, Element)] =
    if (component.isEmpty) {
      Option(subPath -> config.head)
    } else None
}

object IdeaConfigFile {

  /** Alternative creator accepting a component string. */
  def apply(
      subPath: java.nio.file.Path,
      component: String,
      config: Seq[Element]
  ): IdeaConfigFile =
    IdeaConfigFile(subPath, if (component == "") None else Option(component), config)
}
