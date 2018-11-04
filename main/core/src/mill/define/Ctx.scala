package mill.define


import scala.annotation.implicitNotFound

sealed trait Segment{
  def pathSegments: Seq[String] = this match{
    case Segment.Label(s) => List(s)
    case Segment.Cross(vs) => vs.map(_.toString)
  }
}
object Segment{
  case class Label(value: String) extends Segment{
    assert(!value.contains('.'))
  }
  case class Cross(value: Seq[Any]) extends Segment
}

case class BasePath(value: os.Path)


/**
  * Models a path with the Mill build hierarchy, e.g.
  *
  * amm.util[2.11].test.compile
  *
  * .-separated segments are [[Segment.Label]]s, while []-delimited
  * segments are [[Segment.Cross]]s
  */
case class Segments(value: Segment*){
  def ++(other: Seq[Segment]): Segments = Segments(value ++ other:_*)
  def ++(other: Segments): Segments = Segments(value ++ other.value:_*)
  def parts = value.toList match {
    case Nil => Nil
    case Segment.Label(head) :: rest =>
      val stringSegments = rest.flatMap{
        case Segment.Label(s) => Seq(s)
        case Segment.Cross(vs) => vs.map(_.toString)
      }
      head +: stringSegments
  }
  def last : Segments = Segments(value.last)
  def render = value.toList match {
    case Nil => ""
    case Segment.Label(head) :: rest =>
      val stringSegments = rest.map{
        case Segment.Label(s) => "." + s
        case Segment.Cross(vs) => "[" + vs.mkString(",") + "]"
      }
      head + stringSegments.mkString
  }
}

object Segments {

  def labels(values : String*) : Segments =
    Segments(values.map(Segment.Label):_*)

}

@implicitNotFound("Modules, Targets and Commands can only be defined within a mill Module")
case class Ctx(enclosing: String,
               lineNum: Int,
               segment: Segment,
               millSourcePath: os.Path,
               segments: Segments,
               overrides: Int,
               external: Boolean,
               foreign: Boolean,
               fileName: String,
               enclosingCls: Class[_]){
}

object Ctx{
  case class External(value: Boolean)
  case class Foreign(value : Boolean)
  implicit def make(implicit millModuleEnclosing0: sourcecode.Enclosing,
                    millModuleLine0: sourcecode.Line,
                    millName0: sourcecode.Name,
                    millModuleBasePath0: BasePath,
                    segments0: Segments,
                    overrides0: mill.util.Router.Overrides,
                    external0: External,
                    foreign0: Foreign,
                    fileName: sourcecode.File,
                    enclosing: Caller): Ctx = {
    Ctx(
      millModuleEnclosing0.value,
      millModuleLine0.value,
      Segment.Label(millName0.value),
      millModuleBasePath0.value,
      segments0,
      overrides0.value,
      external0.value,
      foreign0.value,
      fileName.value,
      enclosing.value.getClass
    )
  }
}
