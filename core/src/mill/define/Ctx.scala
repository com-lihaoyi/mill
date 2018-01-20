package mill.define

import ammonite.main.Router.Overrides
import ammonite.ops.{Path, RelPath}

import scala.annotation.implicitNotFound

sealed trait Segment {
  def pathSegments: Seq[String] = this match {
    case Segment.Label(s) => List(s)
    case Segment.Cross(vs) => vs.map(_.toString)
  }
}
object Segment {
  case class Label(value: String) extends Segment
  case class Cross(value: Seq[Any]) extends Segment
}

case class BasePath(value: Path)

/**
  * Models a path with the Mill build hierarchy, e.g.
  *
  * amm.util[2.11].test.compile
  *
  * .-separated segments are [[Segment.Label]]s, while []-delimited
  * segments are [[Segment.Cross]]s
  */
case class Segments(value: Segment*) {
  def ++(other: Seq[Segment]): Segments = Segments(value ++ other: _*)
  def ++(other: Segments): Segments = Segments(value ++ other.value: _*)
  def render = value match {
    case Nil => ""
    case Segment.Label(head) :: rest =>
      val stringSegments = rest.map {
        case Segment.Label(s) => "." + s
        case Segment.Cross(vs) => "[" + vs.mkString(",") + "]"
      }
      head + stringSegments.mkString
  }
}

@implicitNotFound(
  "Modules, Targets and Commands can only be defined within a mill Module"
)
case class Ctx(enclosing: String,
               lineNum: Int,
               segment: Segment,
               basePath: Path,
               segments: Segments,
               overrides: Int) {}

object Ctx {
  implicit def make(implicit millModuleEnclosing0: sourcecode.Enclosing,
                    millModuleLine0: sourcecode.Line,
                    millName0: sourcecode.Name,
                    millModuleBasePath0: BasePath,
                    segments0: Segments,
                    overrides0: Overrides): Ctx = {
    Ctx(
      millModuleEnclosing0.value,
      millModuleLine0.value,
      Segment.Label(millName0.value),
      millModuleBasePath0.value,
      segments0,
      overrides0.value
    )
  }
}
