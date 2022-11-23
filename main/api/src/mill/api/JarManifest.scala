package mill.api

import java.util.jar.{Attributes, Manifest}

/**
 * Represents a JAR manifest.
 *
 * @param main   the main manifest attributes
 * @param groups additional attributes for named entries
 */
final case class JarManifest private (
    main: Map[String, String],
    groups: Map[String, Map[String, String]]
) {
  def add(entries: (String, String)*): JarManifest = copy(main = main ++ entries)

  def addGroup(group: String, entries: (String, String)*): JarManifest =
    copy(groups = groups + (group -> (groups.getOrElse(group, Map.empty) ++ entries)))

  private def copy(
      main: Map[String, String] = main,
      groups: Map[String, Map[String, String]] = groups
  ): JarManifest = new JarManifest(main, groups)

  /** Constructs a [[java.util.jar.Manifest]] from this JarManifest. */
  def build: Manifest = {
    val manifest = new Manifest
    val mainAttributes = manifest.getMainAttributes
    main.foreach { case (key, value) => mainAttributes.putValue(key, value) }
    val entries = manifest.getEntries
    for ((group, attribs) <- groups) {
      val attrib = new Attributes
      attribs.foreach { case (key, value) => attrib.putValue(key, value) }
      entries.put(group, attrib)
    }
    manifest
  }
}

object JarManifest {
  implicit val jarManifestRW: upickle.default.ReadWriter[JarManifest] = upickle.default.macroRW

  def apply(
      main: Map[String, String] = Map.empty,
      groups: Map[String, Map[String, String]] = Map.empty
  ): JarManifest = new JarManifest(main, groups)

  private def unapply(jarManifest: JarManifest)
      : Option[(Map[String, String], Map[String, Map[String, String]])] =
    Some(jarManifest.main, jarManifest.groups)
}
