package mill.api.daemon.internal

import mill.api.daemon.{Segment, Segments}
import mill.api.*

object IdeUtils {

  /**
   * Create the module name (to be used by an IDE) for the module based on its segments.
   *
   * @param path If the  segments yield no result, use the Mill Module folder name from this path.
   * @see [[Module.moduleSegments]]
   */
  def moduleName(p: Segments): Option[String] = Some(p.value
    .foldLeft(StringBuilder()) {
      case (sb, Segment.Label(s)) if sb.isEmpty => sb.append(s)
      case (sb, Segment.Cross(s)) if sb.isEmpty => sb.append(s.mkString("-"))
      case (sb, Segment.Label(s)) => sb.append(".").append(s)
      case (sb, Segment.Cross(s)) => sb.append("-").append(s.mkString("-"))
    }
    .mkString
    .toLowerCase()).filterNot(_.isBlank)
}
