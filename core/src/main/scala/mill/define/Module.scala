package mill.define

import ammonite.ops.Path

case class BasePath(value: Path)
/**
  * `Module` is a class meant to be extended by `trait`s *only*, in order to
  * propagate the implicit parameters forward to the final concrete
  * instantiation site so they can capture the enclosing/line information of
  * the concrete instance.
  */
class Module(implicit ctx: Module.Ctx) extends mill.moduledefs.Cacher{
  // Ensure we do not propagate the implicit parameters as implicits within
  // the body of any inheriting class/trait/objects, as it would screw up any
  // one else trying to use sourcecode.{Enclosing,Line} to capture debug info
  val millModuleEnclosing = ctx.millModuleEnclosing0
  val millModuleLine = ctx.millModuleLine0
  def basePath: Path = ctx.millModuleBasePath0.value / ctx.millName0.value
  implicit def millModuleBasePath: BasePath = BasePath(basePath)
}
object Module{
  case class Ctx(millModuleEnclosing0: sourcecode.Enclosing,
                 millModuleLine0: sourcecode.Line,
                 millName0: sourcecode.Name,
                 millModuleBasePath0: BasePath)
  object Ctx{
    implicit def make(implicit millModuleEnclosing0: sourcecode.Enclosing,
                      millModuleLine0: sourcecode.Line,
                      millName0: sourcecode.Name,
                      millModuleBasePath0: BasePath): Ctx = Ctx(
      millModuleEnclosing0,
      millModuleLine0,
      millName0,
      millModuleBasePath0
    )
  }
}
class BaseModule(basePath: Path)
                (implicit millModuleEnclosing0: sourcecode.Enclosing,
                 millModuleLine0: sourcecode.Line,
                 millName0: sourcecode.Name)
  extends Module()(Module.Ctx.make(implicitly, implicitly, implicitly, BasePath(basePath)))