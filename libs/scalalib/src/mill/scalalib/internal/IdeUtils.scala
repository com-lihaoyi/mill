package mill.scalalib.internal

import mill.api.daemon.Segments
import mill.api.*

import java.nio.file.Path

object IdeUtils {
  /**
   * Create the module name (to be used by an IDE) for the module based on its segments.
   *
   * @param path If the  segments yield no result, use the Mill Module folder name from this path.
   * @see [[Module.moduleSegments]]
   */
  def moduleName(p: Segments, path: Path = null): String = {
    val name = p.value
      .foldLeft(new StringBuilder()) {
        case (sb, Segment.Label(s)) if sb.isEmpty => sb.append(s)
        case (sb, Segment.Cross(s)) if sb.isEmpty => sb.append(s.mkString("-"))
        case (sb, Segment.Label(s)) => sb.append(".").append(s)
        case (sb, Segment.Cross(s)) => sb.append("-").append(s.mkString("-"))
      }
      .mkString
      .toLowerCase()

    // If for whatever reason no name could be created based on the module segments, create a
    // generic one from the Mill Module folder name. Users can rename the project inside the
    // IDE if they like.
    if (name.isBlank && path != null) path.getFileName.toString
    else name
  }
}
