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
    ) with mill.main.MainModule

@internal
object RootModule {
  case class Info(millSourcePath0: os.Path, discover: Discover)
  case class SubFolderInfo(value: Seq[String])

  abstract class Subfolder()(implicit
      baseModuleInfo: RootModule.Info,
      millModuleLine0: sourcecode.Line,
      millFile0: sourcecode.File,
      subFolderInfo: SubFolderInfo
  ) extends Module.BaseClass()(
        Ctx.make(
          millModuleEnclosing0 = subFolderInfo.value.mkString("."),
          millModuleLine0 = millModuleLine0,
          millModuleBasePath0 = Ctx.BasePath(baseModuleInfo.millSourcePath0 / os.up),
          segments0 = Segments.labels(subFolderInfo.value.init: _*),
          external0 = Ctx.External(false),
          foreign0 = Ctx.Foreign(None),
          fileName = millFile0,
          enclosing = Caller(null)
        )
      ) with Module {
    def millDiscover: Discover
  }

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
      ) with mill.main.MainModule
}
