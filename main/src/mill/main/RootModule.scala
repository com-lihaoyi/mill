package mill.main

import mill.api.internal
import mill.define.{Caller, Discover, Segments}

/**
 * Used to mark a module in your `build.sc` as a top-level module, so it's
 * targets and commands can be run directly e.g. via `mill run` rather than
 * prefixed by the module name `mill foo.run`.
 *
 * Only one top-level module may be defined in your `build.sc`, and it must be
 * defined at the top level of the `build.sc` and not nested in any other
 * modules.
 */
abstract class RootModule(implicit
    baseModuleInfo: RootModule.Info,
    millModuleEnclosing0: sourcecode.Enclosing,
    millModuleLine0: sourcecode.Line,
    millFile0: sourcecode.File,
    ctx: mill.define.Ctx
) extends RootModule.Base(foreign0 = ctx.foreign)

abstract class RootModuleForeign(implicit
    baseModuleInfo: RootModule.Info,
    millModuleEnclosing0: sourcecode.Enclosing,
    millModuleLine0: sourcecode.Line,
    millFile0: sourcecode.File,
    ctx: mill.define.Ctx
) extends RootModule.Foreign(foreign0 = ctx.foreign)

@internal
object RootModule {
  abstract class Base(foreign0: Option[Segments] = None)(implicit
      baseModuleInfo: RootModule.Info,
      millModuleEnclosing0: sourcecode.Enclosing,
      millModuleLine0: sourcecode.Line,
      millFile0: sourcecode.File
  ) extends mill.define.BaseModule(baseModuleInfo.millSourcePath0, foreign0 = foreign0)(
        millModuleEnclosing0,
        millModuleLine0,
        millFile0,
        Caller(null)
      ) with mill.main.MainModule {
    def this(segments: String*)(implicit
        baseModuleInfo: RootModule.Info,
        millModuleEnclosing0: sourcecode.Enclosing,
        millModuleLine0: sourcecode.Line,
        millFile0: sourcecode.File
    ) = this(Some(Segments.labels(segments: _*)))

    // Make BaseModule take the `millDiscover` as an implicit param, rather than
    // defining it itself. That is so we can define it externally in the wrapper
    // code and it have it automatically passed to both the wrapper BaseModule as
    // well as any user-defined BaseModule that may be present, so the
    // user-defined BaseModule can have a complete Discover[_] instance without
    // needing to tediously call `override lazy val millDiscover = Discover[this.type]`
    override lazy val millDiscover: Discover[this.type] =
      baseModuleInfo.discover.asInstanceOf[Discover[this.type]]
  }
  case class Info(millSourcePath0: os.Path, discover: Discover[_])

  abstract class Foreign(foreign0: Option[Segments])(implicit
      baseModuleInfo: RootModule.Info,
      millModuleEnclosing0: sourcecode.Enclosing,
      millModuleLine0: sourcecode.Line,
      millFile0: sourcecode.File
  ) extends mill.define.BaseModule(baseModuleInfo.millSourcePath0, foreign0 = foreign0)(
        millModuleEnclosing0,
        millModuleLine0,
        millFile0,
        Caller(null)
      ) {
    def this(segments: String*)(implicit
        baseModuleInfo: RootModule.Info,
        millModuleEnclosing0: sourcecode.Enclosing,
        millModuleLine0: sourcecode.Line,
        millFile0: sourcecode.File
    ) = this(Some(Segments.labels(segments: _*)))
    object interp extends Interp

    override lazy val millDiscover: Discover[this.type] =
      baseModuleInfo.discover.asInstanceOf[Discover[this.type]]
  }
}
