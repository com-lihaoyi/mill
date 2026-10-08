package mill.api.daemon.internal.idea

import com.lihaoyi.unroll

/**
 * Encoding of an Idea XML configuration fragment.
 *
 * @param name The XML element name
 * @param attributes The optional XML element attributes
 * @param childs Deprecated. The optional XML child elements.
 * @param childsOrText The optional XML child elements or text content.
 */
final case class Element(
    name: String,
    attributes: Map[String, String] = Map(),
    @deprecated(
      "Using Elements only is insufficient to represent PCData in XML. Use childsOrText instead.",
      since = "Mill after 1.1.6"
    ) childs: Seq[Element] = Seq(),
    @unroll childsOrText: Seq[Element | String] = Seq()
)
