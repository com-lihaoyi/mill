package mill.define
import mill.runner.api.BaseModuleApi

/**
 * Represents a module at the root of a module tree. This can either be a
 * `mill.define.RootModule` representing the `build.mill` file, or a
 * `mill.define.ExternalModule` provided by a library.
 */
abstract class BaseModule(
    millSourcePath0: os.Path,
    external0: Boolean = false
)(implicit
    millModuleEnclosing0: sourcecode.Enclosing,
    millModuleLine0: sourcecode.Line,
    millFile0: sourcecode.File
) extends Module.BaseClass()(
      mill.define.Ctx.makeRoot(
        implicitly,
        implicitly,
        millSourcePath0,
        Segments(),
        external0,
        millFile0
      )
    ) with Module with BaseModuleApi {

  // `Discover` needs to be defined by every concrete `BaseModule` object, to gather
  // compile-time metadata about the tasks and commands at for use at runtime
  protected def millDiscover: Discover

  // We need to propagate the `Discover` object implicitly throughout the module tree
  // so it can be used for override detection
  def moduleCtx = super.moduleCtx.withDiscover(millDiscover)
}
