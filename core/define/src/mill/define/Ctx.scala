package mill.define

import mill.define.internal.OverrideMapping

import scala.annotation.{compileTimeOnly, implicitNotFound}

/**
 * The contextual information provided to a [[mill.define.Module]] or [[mill.define.Task]]
 */
@implicitNotFound("Modules and Tasks can only be defined within a mill Module")
trait Ctx extends Ctx.Nested {
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
   * the file name that this module is defined in. Useful for
   * error reporting purposes
   */
  def fileName: String

  /**
   * The `class` or `trait` that lexically surrounds this definition
   */
  def enclosingCls: Class[?]


  def crossValues: Seq[Any]

  private[mill] def withCrossValues(crossValues: Seq[Any]): Ctx
  private[mill] def withMillSourcePath(millSourcePath: os.Path): Ctx
  private[mill] def withSegment(segment: Segment): Ctx
  private[mill] def withSegments(segments: Segments): Ctx
  private[mill] def withEnclosingModule(enclosingModule: Ctx.Wrapper): Ctx
  private[mill] def withDiscover(discover: Discover): Ctx
}

object Ctx extends LowPriCtx {
  trait Wrapper {
    def millOuterCtx: Ctx
    private[mill] def linearized: Seq[Class[?]]
  }
  private case class Impl(
      enclosing: String,
      lineNum: Int,
      segment: Segment,
      millSourcePath: os.Path,
      segments: Segments,
      external: Boolean,
      fileName: String,
      enclosingModule: Ctx.Wrapper,
      crossValues: Seq[Any],
      discover: Discover
  ) extends Ctx {
    def enclosingCls = enclosingModule.getClass
    def withCrossValues(crossValues: Seq[Any]): Ctx = copy(crossValues = crossValues)
    def withMillSourcePath(millSourcePath: os.Path): Ctx = copy(millSourcePath = millSourcePath)
    def withSegment(segment: Segment): Ctx = copy(segment = segment)
    def withSegments(segments: Segments): Ctx = copy(segments = segments)
    def withEnclosingModule(enclosingModule: Ctx.Wrapper): Ctx =
      copy(enclosingModule = enclosingModule)
    def withDiscover(discover: Discover): Ctx = copy(discover = discover)
  }

  /**
   * A subset of the [[Ctx]] interface, used to implicitly propagate the
   * necessary fields down the module hierarchy
   */
  trait Nested {
    /**
     * The runtime [[Module]] object that contains this definition
     */
    def enclosingModule: Ctx.Wrapper
    /**
     * The enclosing module's default source root
     */
    private[mill] def millSourcePath: os.Path

    /**
     * The full path of this task or module, from the [[BaseModule]]
     */
    private[mill] def segments: Segments

    /**
     * whether this is in an [[ExternalModule]]
     */
    private[mill] def external: Boolean

    /**
     * The [[Discover]] instance associate with this [[BaseModule]] hierarchy
     */
    private[mill] def discover: Discover
  }
  implicit def implicitMake(
      implicit
      millModuleEnclosing0: sourcecode.Enclosing,
      millModuleLine0: sourcecode.Line,
      fileName: sourcecode.File,
      enclosingClass: EnclosingClass,
      ctx: Ctx.Nested
  ): Ctx = {
    make(
      millModuleEnclosing0,
      millModuleLine0,
      ctx.millSourcePath,
      ctx.segments,
      ctx.external,
      fileName,
      ctx.enclosingModule,
      enclosingClass,
      ctx.discover
    )
  }
  def make(
      millModuleEnclosing0: sourcecode.Enclosing,
      millModuleLine0: sourcecode.Line,
      millSourcePath: os.Path,
      segments0: Segments,
      external0: Boolean,
      fileName: sourcecode.File,
      enclosingModule: Ctx.Wrapper,
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
      millSourcePath,
      segments0 ++
        OverrideMapping.computeSegments(
          enclosingModule,
          discover,
          lastSegmentStr,
          enclosingClass.value
        ),
      external0,
      fileName.value,
      enclosingModule,
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
