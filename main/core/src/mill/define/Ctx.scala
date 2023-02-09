package mill.define

import scala.annotation.implicitNotFound

case class BasePath(value: os.Path)

@implicitNotFound("Modules, Targets and Commands can only be defined within a mill Module")
case class Ctx(
    enclosing: String,
    lineNum: Int,
    segment: Segment,
    millSourcePath: os.Path,
    segments: Segments,
    external: Boolean,
    foreign: Option[Segments],
    fileName: String,
    enclosingCls: Class[_],
    crossInstances: Seq[AnyRef]
) {}

object Ctx {
  case class External(value: Boolean)
  case class Foreign(value: Option[Segments])
  implicit def make(implicit
      millModuleEnclosing0: sourcecode.Enclosing,
      millModuleLine0: sourcecode.Line,
      millName0: sourcecode.Name,
      millModuleBasePath0: BasePath,
      segments0: Segments,
      external0: External,
      foreign0: Foreign,
      fileName: sourcecode.File,
      enclosing: Caller
  ): Ctx = {
    Ctx(
      millModuleEnclosing0.value,
      millModuleLine0.value,
      Segment.Label(millName0.value),
      millModuleBasePath0.value,
      segments0,
      external0.value,
      foreign0.value,
      fileName.value,
      enclosing.value.getClass,
      Seq()
    )
  }
}
