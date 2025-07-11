package mill.api.daemon.internal.idea

/**
 * Encoding of an Idea XML configuration fragment.
 * @param name The XML element name
 * @param attributes The optional XML element attributes
 * @param childs The optional XML child elements.
 */
final case class Element(
    name: String,
    attributes: Map[String, String] = Map(),
    childs: Seq[Element] = Seq()
)
