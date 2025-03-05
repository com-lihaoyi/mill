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
   * the file name that this module is defined in. Useful for
   * error reporting purposes
   */
  def fileName: String

  /**
   * The `class` or `trait` that lexically surrounds this definition
   */
  private[mill] def enclosingCls: Class[?]

  private[mill] def withCrossValues(crossValues: Seq[Any]): Ctx
  private[mill] def withMillSourcePath(millSourcePath: os.Path): Ctx
  private[mill] def withSegments(segments: Segments): Ctx
  private[mill] def withEnclosingModule(enclosingModule: Ctx.Wrapper): Ctx
  private[mill] def withDiscover(discover: Discover): Ctx
}

object Ctx extends LowPriCtx {
  trait Wrapper {
    def moduleCtx: Ctx
    private[mill] def moduleLinearized: Seq[Class[?]]
  }
  private case class Impl(
      enclosing: String,
      lineNum: Int,
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
    def withSegments(segments: Segments): Ctx = copy(segments = segments)
    def withEnclosingModule(enclosingModule: Ctx.Wrapper): Ctx =
      copy(enclosingModule = enclosingModule)
    def withDiscover(discover: Discover): Ctx = copy(discover = discover)
  }

  /**
   * A subset of the [[Ctx]] interface that are implicitly propagated
   * from the enclosing Module.
   */
  trait Nested {

    /**
     * The runtime [[Module]] object that contains this definition
     */
    private[mill] def enclosingModule: Ctx.Wrapper

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

    def crossValues: Seq[Any]
  }
  implicit def makeNested(
      implicit
      millModuleEnclosing0: sourcecode.Enclosing,
      millModuleLine0: sourcecode.Line,
      fileName: sourcecode.File,
      enclosingClass: EnclosingClass,
      ctx: Ctx.Nested
  ): Ctx = {
    // Manually break apart `sourcecode.Enclosing` instead of using
    // `sourcecode.Name` to work around bug with anonymous classes
    // returning `$anon` names
    val lastSegmentStr =
      millModuleEnclosing0.value.split("\\.|#| ").filter(!_.startsWith("$anon")).last

    Impl(
      millModuleEnclosing0.value,
      millModuleLine0.value,
      ctx.millSourcePath / lastSegmentStr,
      ctx.segments ++
        OverrideMapping.computeSegments(
          ctx.enclosingModule,
          ctx.discover,
          lastSegmentStr,
          enclosingClass.value
        ).getOrElse(Segments(List(Segment.Label(lastSegmentStr)))),
      ctx.external,
      fileName.value,
      ctx.enclosingModule,
      Nil,
      ctx.discover
    )
  }
  def makeRoot(
      millModuleEnclosing0: sourcecode.Enclosing,
      millModuleLine0: sourcecode.Line,
      millSourcePath: os.Path,
      segments0: Segments,
      external0: Boolean,
      fileName: sourcecode.File
  ): Ctx = {

    Impl(
      millModuleEnclosing0.value,
      millModuleLine0.value,
      millSourcePath,
      segments0,
      external0,
      fileName.value,
      null,
      Seq(),
      null
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
