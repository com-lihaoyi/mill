package mill.define

import ammonite.main.Router.Overrides
import ammonite.ops.Path

import scala.annotation.implicitNotFound

sealed trait Segment
object Segment{
  case class Label(value: String) extends Segment
  case class Cross(value: Seq[Any]) extends Segment
}

case class BasePath(value: Path)
case class Segments(value: Seq[Segment])
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
    Segments(ctx.segments0.value :+ ctx.segment)
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
    def segments = Segments(segments0.value.drop(1))
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
    Module.Ctx.make(implicitly, implicitly, implicitly, BasePath(basePath), Segments(Nil), implicitly)
  )