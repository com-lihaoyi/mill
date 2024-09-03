package mill.main

import mill.api.internal
import mill.define.{BaseModule, Ctx, Caller, Discover, Module, Segments}

/**
 * Used to mark a module in your `build.mill` as a top-level module, so it's
 * targets and commands can be run directly e.g. via `mill run` rather than
 * prefixed by the module name `mill foo.run`.
 *
 * Only one top-level module may be defined in your `build.mill`, and it must be
 * defined at the top level of the `build.mill` and not nested in any other
 * modules.
 */
abstract class RootModule()(implicit
    baseModuleInfo: RootModule.Info,
    millModuleEnclosing0: sourcecode.Enclosing,
    millModuleLine0: sourcecode.Line,
    millFile0: sourcecode.File
) extends mill.define.BaseModule(baseModuleInfo.millSourcePath0)(
      millModuleEnclosing0,
      millModuleLine0,
      millFile0,
      Caller(null)
    ) with mill.main.MainModule {

  // Make BaseModule take the `millDiscover` as an implicit param, rather than
  // defining it itself. That is so we can define it externally in the wrapper
  // code and it have it automatically passed to both the wrapper BaseModule as
  // well as any user-defined BaseModule that may be present, so the
  // user-defined BaseModule can have a complete Discover[_] instance without
  // needing to tediously call `override lazy val millDiscover = Discover[this.type]`
  override lazy val millDiscover: Discover[this.type] =
    baseModuleInfo.discover.asInstanceOf[Discover[this.type]]
}

@internal
object RootModule {
  case class Info(millSourcePath0: os.Path, discover: Discover[_])

  abstract class Subfolder(path: String*)(implicit
      baseModuleInfo: RootModule.Info,
      millModuleLine0: sourcecode.Line,
      millFile0: sourcecode.File
  ) extends Module.BaseClass()(
        Ctx.make(
          millModuleEnclosing0 = path.mkString("."),
          millModuleLine0 = millModuleLine0,
          millModuleBasePath0 = Ctx.BasePath(baseModuleInfo.millSourcePath0 / os.up),
          segments0 = Segments.labels(path.init: _*),
          external0 = Ctx.External(false),
          foreign0 = Ctx.Foreign(None),
          fileName = millFile0,
          enclosing = Caller(null)
        )
      ) with Module

  @deprecated
  abstract class Foreign(foreign0: Option[Segments])(implicit
      baseModuleInfo: RootModule.Info,
      millModuleEnclosing0: sourcecode.Enclosing,
      millModuleLine0: sourcecode.Line,
      millFile0: sourcecode.File
  ) extends BaseModule(baseModuleInfo.millSourcePath0, foreign0 = foreign0)(
        millModuleEnclosing0,
        millModuleLine0,
        millFile0,
        Caller(null)
      ) with mill.main.MainModule {

    override implicit lazy val millDiscover: Discover[this.type] = Discover[this.type]
  }
}
