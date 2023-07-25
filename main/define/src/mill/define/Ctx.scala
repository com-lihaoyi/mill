package mill.define

import os.Path

import scala.annotation.implicitNotFound

/**
 * The contextual information provided by a [[mill.define.Module]].
 *
 * @param enclosing
 * @param lineNum the line number that this module is defined at. Useful for
 *                error reporting purposes
 * @param segment
 * @param millSourcePath
 * @param segments
 * @param external
 * @param foreign
 * @param fileName the file name that this module is defined in. Useful for
 *                 error reporting purposes
 * @param enclosingCls
 * @param crossValues
 */
@implicitNotFound("Modules, Targets and Commands can only be defined within a mill Module")
trait Ctx {
  def enclosing: String
  def lineNum: Int
  def segment: Segment
  def millSourcePath: os.Path
  def segments: Segments
  def external: Boolean
  def foreign: Option[Segments]
  def fileName: String
  def enclosingCls: Class[_]
  def enclosingModule: Any = null
  def crossValues: Seq[Any]

  private[mill] def withCrossValues(crossValues: Seq[Any]): Ctx
  private[mill] def withMillSourcePath(millSourcePath: os.Path): Ctx
  private[mill] def withSegment(segment: Segment): Ctx
  private[mill] def withSegments(segments: Segments): Ctx
  private[mill] def withEnclosingModule(enclosingModule: Any): Ctx = this
}

object Ctx {
  private case class Impl(
      enclosing: String,
      lineNum: Int,
      segment: Segment,
      millSourcePath: os.Path,
      segments: Segments,
      external: Boolean,
      foreign: Option[Segments],
      fileName: String,
      override val enclosingModule: Any,
      crossValues: Seq[Any]
  ) extends Ctx {
    def enclosingCls = enclosingModule.getClass
    def withCrossValues(crossValues: Seq[Any]): Ctx = copy(crossValues = crossValues)
    def withMillSourcePath(millSourcePath: os.Path): Ctx = copy(millSourcePath = millSourcePath)
    def withSegment(segment: Segment): Ctx = copy(segment = segment)
    def withSegments(segments: Segments): Ctx = copy(segments = segments)
    override def withEnclosingModule(enclosingModule: Any): Ctx = copy(enclosingModule = enclosingModule)
  }

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
      millModuleBasePath0: BasePath,
      segments0: Segments,
      external0: External,
      foreign0: Foreign,
      fileName: sourcecode.File,
      enclosing: Caller
  ): Ctx = {
    Impl(
      millModuleEnclosing0.value,
      millModuleLine0.value,
      Segment.Label(
        // Manually break apart `sourcecode.Enclosing` instead of using
        // `sourcecode.Name` to work around bug with anonymous classes
        // returning `$anon` names
        millModuleEnclosing0.value.split("\\.|#| ").filter(!_.startsWith("$anon")).last
      ),
      millModuleBasePath0.value,
      segments0,
      external0.value,
      foreign0.value,
      fileName.value,
      enclosing.value,
      Seq()
    )
  }
}
