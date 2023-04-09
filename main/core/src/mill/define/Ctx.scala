package mill.define

import os.Path

import scala.annotation.implicitNotFound

@implicitNotFound("Modules, Targets and Commands can only be defined within a mill Module")
case class Ctx private (
    enclosing: String,
    lineNum: Int,
    segment: Segment,
    millSourcePath: os.Path,
    segments: Segments,
    external: Boolean,
    foreign: Option[Segments],
    fileName: String,
    enclosingCls: Class[_],
    crossValues: Seq[Any]
) {
  private def copy(
      enclosing: String = enclosing,
      lineNum: Int = lineNum,
      segment: Segment = segment,
      millSourcePath: os.Path = millSourcePath,
      segments: Segments = segments,
      external: Boolean = external,
      foreign: Option[Segments] = foreign,
      fileName: String = fileName,
      enclosingCls: Class[_] = enclosingCls,
      crossValues: Seq[Any] = crossValues
  ): Ctx = new Ctx(
    enclosing,
    lineNum,
    segment,
    millSourcePath,
    segments,
    external,
    foreign,
    fileName,
    enclosingCls,
    crossValues
  )
  def withCrossValues(crossValues: Seq[Any]): Ctx = copy(crossValues = crossValues)
  def withMillSourcePath(millSourcePath: os.Path): Ctx = copy(millSourcePath = millSourcePath)
  def withSegment(segment: Segment): Ctx = copy(segment = segment)
  def withSegments(segments: Segments): Ctx = copy(segments = segments)
}

object Ctx {

  /**
   * Marker for a base path to be used implicitly by [[Ctx]].
   */
  final case class BasePath(value: os.Path)

  /**
   * Marker for the external flog to be used implicitly by [[Ctx]].
   * @param value
   */
  final case class External(value: Boolean)

  /**
   * Marker for the foreign module segments of a module to be used implicitly by [[Ctx]].
   */
  final case class Foreign(value: Option[Segments])

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

  def apply(
      enclosing: String,
      lineNum: Int,
      segment: Segment,
      millSourcePath: os.Path,
      segments: Segments,
      external: Boolean,
      foreign: Option[Segments],
      fileName: String,
      enclosingCls: Class[_],
      crossValues: Seq[Any]
  ): Ctx = new Ctx(
    enclosing,
    lineNum,
    segment,
    millSourcePath,
    segments,
    external,
    foreign,
    fileName,
    enclosingCls,
    crossValues
  )
  private def unapply(ctx: Ctx): Option[(
      String,
      Int,
      Segment,
      Path,
      Segments,
      Boolean,
      Option[Segments],
      String,
      Class[_],
      Seq[Any]
  )] = Some((
    ctx.enclosing,
    ctx.lineNum,
    ctx.segment,
    ctx.millSourcePath,
    ctx.segments,
    ctx.external,
    ctx.foreign,
    ctx.fileName,
    ctx.enclosingCls,
    ctx.crossValues
  ))
}
