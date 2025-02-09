package mill.define

import scala.annotation.{compileTimeOnly, implicitNotFound}

/**
 * The contextual information provided to a [[mill.define.Module]] or [[mill.define.Task]]
 */
@implicitNotFound("Modules and Tasks can only be defined within a mill Module")
trait Ctx {
  def enclosing: String

  /**
   * the line number that this module is defined at. Useful for
   * error reporting purposes
   */
  def lineNum: Int

  /**
   * The name of this task or module
   */
  def segment: Segment

  /**
   * The enclosing module's default source root
   */
  def millSourcePath: os.Path

  /**
   * The full path of this task or module, from the [[BaseModule]]
   */
  def segments: Segments

  /**
   * whether this is in an [[ExternalModule]]
   */
  def external: Boolean

  /**
   * the file name that this module is defined in. Useful for
   * error reporting purposes
   */
  def fileName: String

  /**
   * The `class` or `trait` that lexically surrounds this definition
   */
  def enclosingCls: Class[?]

  /**
   * The runtime [[Module]] object that contains this definition
   */
  def enclosingModule: Any
  def crossValues: Seq[Any]

  /**
   * The [[Discover]] instance associate with this [[BaseModule]] hierarchy
   */
  def discover: Discover

  private[mill] def withCrossValues(crossValues: Seq[Any]): Ctx
  private[mill] def withMillSourcePath(millSourcePath: os.Path): Ctx
  private[mill] def withSegment(segment: Segment): Ctx
  private[mill] def withSegments(segments: Segments): Ctx
  private[mill] def withEnclosingModule(enclosingModule: Any): Ctx
  private[mill] def withDiscover(discover: Discover): Ctx
}

object Ctx extends LowPriCtx {
  private case class Impl(
      enclosing: String,
      lineNum: Int,
      segment: Segment,
      millSourcePath: os.Path,
      segments: Segments,
      external: Boolean,
      fileName: String,
      enclosingModule: Any,
      crossValues: Seq[Any],
      discover: Discover
  ) extends Ctx {
    def enclosingCls = enclosingModule.getClass
    def withCrossValues(crossValues: Seq[Any]): Ctx = copy(crossValues = crossValues)
    def withMillSourcePath(millSourcePath: os.Path): Ctx = copy(millSourcePath = millSourcePath)
    def withSegment(segment: Segment): Ctx = copy(segment = segment)
    def withSegments(segments: Segments): Ctx = copy(segments = segments)
    def withEnclosingModule(enclosingModule: Any): Ctx = copy(enclosingModule = enclosingModule)
    def withDiscover(discover: Discover): Ctx = copy(discover = discover)
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

  implicit def make(implicit
      millModuleEnclosing0: sourcecode.Enclosing,
      millModuleLine0: sourcecode.Line,
      millModuleBasePath0: BasePath,
      segments0: Segments,
      external0: External,
      fileName: sourcecode.File,
      enclosingModule: Caller[OverrideMapping.Wrapper],
      enclosingClass: EnclosingClass,
      discover: Discover
  ): Ctx = {
    // Manually break apart `sourcecode.Enclosing` instead of using
    // `sourcecode.Name` to work around bug with anonymous classes
    // returning `$anon` names
    val lastSegmentStr =
      millModuleEnclosing0.value.split("\\.|#| ").filter(!_.startsWith("$anon")).last
    Impl(
      millModuleEnclosing0.value,
      millModuleLine0.value,
      Segment.Label(lastSegmentStr),
      millModuleBasePath0.value,
      segments0 ++
        OverrideMapping.computeSegments(
          enclosingModule.value,
          discover,
          lastSegmentStr,
          enclosingClass.value
        ),
      external0.value,
      fileName.value,
      enclosingModule.value,
      Seq(),
      discover
    )
  }
}

trait LowPriCtx {
  // Dummy `Ctx` available in implicit scope but never actually used.
  // as it is provided by the codegen. Defined for IDEs to think that one is available
  // and not show errors in build.mill/package.mill even though they can't see the codegen
  @compileTimeOnly(
    "Modules and Tasks can only be defined within a mill Module"
  )
  implicit def dummyInfo: Ctx = sys.error("implicit Ctx must be provided")
}
