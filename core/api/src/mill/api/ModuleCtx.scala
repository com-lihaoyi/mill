package mill.api

import mill.api.internal.OverrideMapping

import scala.annotation.implicitNotFound

/**
 * The contextual information provided to a [[mill.api.Module]] or [[mill.api.Task]]
 */
@implicitNotFound(
  "Modules and Tasks can only be defined within a mill Module (in `build.mill` or `package.mill` files)"
)
trait ModuleCtx extends ModuleCtx.Nested {
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

  private[mill] def withCrossValues(crossValues: Seq[Any]): ModuleCtx
  private[mill] def withMillSourcePath(millSourcePath: os.Path): ModuleCtx
  private[mill] def withSegments(segments: Segments): ModuleCtx
  private[mill] def withEnclosingModule(enclosingModule: ModuleCtx.Wrapper): ModuleCtx
  private[mill] def withDiscover(discover: Discover): ModuleCtx
}

object ModuleCtx extends LowPriCtx {
  trait Wrapper {
    def moduleCtx: ModuleCtx
    private[mill] def moduleLinearized: Seq[Class[?]]
    private[mill] def buildOverrides: Map[String, ujson.Value] = Map()
    private[mill] def buildOverridePaths: Seq[os.Path] = Nil
  }

  private[mill] case class HeaderData(
      `extends`: Seq[String] = Nil,
      moduleDeps: Seq[String] = Nil,
      compileModuleDeps: Seq[String] = Nil,
      runModuleDeps: Seq[String] = Nil,
      @upickle.implicits.flatten rest: Map[String, ujson.Value]
  ) derives upickle.ReadWriter

  private case class Impl(
      enclosing: String,
      lineNum: Int,
      millSourcePath: os.Path,
      segments: Segments,
      external: Boolean,
      fileName: String,
      enclosingModule: ModuleCtx.Wrapper,
      crossValues: Seq[Any],
      discover: Discover
  ) extends ModuleCtx {
    def enclosingCls = enclosingModule.getClass
    def withCrossValues(crossValues: Seq[Any]): ModuleCtx = copy(crossValues = crossValues)
    def withMillSourcePath(millSourcePath: os.Path): ModuleCtx =
      copy(millSourcePath = millSourcePath)
    def withSegments(segments: Segments): ModuleCtx = copy(segments = segments)
    def withEnclosingModule(enclosingModule: ModuleCtx.Wrapper): ModuleCtx =
      copy(enclosingModule = enclosingModule)
    def withDiscover(discover: Discover): ModuleCtx = copy(discover = discover)
  }

  /**
   * A subset of the [[ModuleCtx]] interface that are implicitly propagated
   * from the enclosing Module.
   */
  trait Nested {

    /**
     * The runtime [[Module]] object that contains this definition
     */
    private[mill] def enclosingModule: ModuleCtx.Wrapper

    /**
     * The enclosing module's default source root
     */
    private[mill] def millSourcePath: os.Path

    /**
     * The full path of this task or module, from the [[RootModule0]]
     */
    private[mill] def segments: Segments

    /**
     * whether this is in an [[ExternalModule]]
     */
    private[mill] def external: Boolean

    /**
     * The [[Discover]] instance associate with this [[RootModule0]] hierarchy
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
      ctx: ModuleCtx.Nested
  ): ModuleCtx = {
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
  ): ModuleCtx = {

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
  // Dummy `Ctx` available in implicit scope but must not be actually used.
  // as it is provided by the codegen. Defined for IDEs to think that one is available
  // and not show errors in build.mill/package.mill even though they can't see the codegen
  implicit def dummyInfo: ModuleCtx = sys.error(
    "Modules and Tasks can only be defined within a mill Module (in `build.mill` or `package.mill` files)"
  )
}
