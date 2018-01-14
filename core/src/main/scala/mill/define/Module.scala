package mill.define

import java.lang.reflect.Modifier

import ammonite.main.Router.Overrides
import ammonite.ops.Path

import scala.annotation.implicitNotFound
import scala.reflect.ClassTag

sealed trait Segment
object Segment{
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
case class Segments(value: Segment*){
  def ++(other: Seq[Segment]): Segments = Segments(value ++ other:_*)
  def ++(other: Segments): Segments = Segments(value ++ other.value:_*)
  def render = value match {
    case Nil => ""
    case Segment.Label(head) :: rest =>
      val stringSegments = rest.map{
        case Segment.Label(s) => "." + s
        case Segment.Cross(vs) => "[" + vs.mkString(",") + "]"
      }
      head + stringSegments.mkString
  }
}
/**
  * `Module` is a class meant to be extended by `trait`s *only*, in order to
  * propagate the implicit parameters forward to the final concrete
  * instantiation site so they can capture the enclosing/line information of
  * the concrete instance.
  */
class Module(implicit ctx0: Module.Ctx) extends mill.moduledefs.Cacher{
  def ctx = ctx0
  // Ensure we do not propagate the implicit parameters as implicits within
  // the body of any inheriting class/trait/objects, as it would screw up any
  // one else trying to use sourcecode.{Enclosing,Line} to capture debug info
  val millModuleEnclosing = ctx.enclosing
  val millModuleLine = ctx.lineNum
  def basePath: Path = ctx.basePath / (ctx.segment match{
    case Segment.Label(s) => List(s)
    case Segment.Cross(vs) => vs.map(_.toString)
  })
  implicit def millModuleBasePath: BasePath = BasePath(basePath)
  implicit def millModuleSegments: Segments = {
    ctx.segments0 ++ Seq(ctx.segment)
  }
  def reflect[T: ClassTag] = {
    this
      .getClass
      .getMethods
      .filter(_.getParameterCount == 0)
      .filter(x => (x.getModifiers & Modifier.STATIC) == 0)
      .filter(implicitly[ClassTag[T]].runtimeClass isAssignableFrom _.getReturnType)
      .map(_.invoke(this).asInstanceOf[T])
  }
}
object Module{
  @implicitNotFound("Modules, Targets and Commands can only be defined within a mill Module")
  case class Ctx(enclosing: String,
                 lineNum: Int,
                 segment: Segment,
                 basePath: Path,
                 segments0: Segments,
                 overrides: Int){
    def segments = Segments(segments0.value.drop(1):_*)
  }
  object Ctx{
    implicit def make(implicit millModuleEnclosing0: sourcecode.Enclosing,
                      millModuleLine0: sourcecode.Line,
                      millName0: sourcecode.Name,
                      millModuleBasePath0: BasePath,
                      segments0: Segments,
                      overrides0: Overrides): Ctx = Ctx(
      millModuleEnclosing0.value,
      millModuleLine0.value,
      Segment.Label(millName0.value),
      millModuleBasePath0.value,
      segments0,
      overrides0.value
    )
  }
}
class BaseModule(basePath: Path)
                (implicit millModuleEnclosing0: sourcecode.Enclosing,
                 millModuleLine0: sourcecode.Line,
                 millName0: sourcecode.Name,
                 overrides0: Overrides)
  extends Module()(
    Module.Ctx.make(implicitly, implicitly, implicitly, BasePath(basePath), Segments(), implicitly)
  )