package mill.main

import mill.api.{PathRef, internal}
import mill.util.Watchable
import mill.define.{Caller, Discover, Segments}
import TokenReaders._

import scala.collection.mutable

/**
 * Used to mark a module in your `build.sc` as a top-level module, so it's
 * targets and commands can be run directly e.g. via `mill run` rather than
 * prefixed by the module name `mill foo.run`.
 *
 * Only one top-level module may be defined in your `build.sc`, and it must be
 * defined at the top level of the `build.sc` and not nested in any other
 * modules.
 */
abstract class RootModule()(implicit
    baseModuleInfo: RootModule.Info,
    millModuleEnclosing0: sourcecode.Enclosing,
    millModuleLine0: sourcecode.Line,
    millName0: sourcecode.Name,
    millFile0: sourcecode.File
) extends mill.define.BaseModule(baseModuleInfo.millSourcePath0)(
      millModuleEnclosing0,
      millModuleLine0,
      millName0,
      millFile0,
      Caller(())
    ) with mill.main.MainModule {

  // Make BaseModule take the `millDiscover` as an implicit param, rather than
  // defining it itself. That is so we can define it externally in the wrapper
  // code and it have it automatically passed to both the wrapper BaseModule as
  // well as any user-defined BaseModule that may be present, so the
  // user-defined BaseModule can have a complete Discover[_] instance without
  // needing to tediously call `override lazy val millDiscover = Discover[this.type]`
  override lazy val millDiscover = baseModuleInfo.discover.asInstanceOf[Discover[this.type]]
}

@internal
object RootModule {
  case class Info(millSourcePath0: os.Path, discover: Discover[_])

  abstract class Foreign(foreign0: Option[Segments])(implicit
      baseModuleInfo: RootModule.Info,
      millModuleEnclosing0: sourcecode.Enclosing,
      millModuleLine0: sourcecode.Line,
      millName0: sourcecode.Name,
      millFile0: sourcecode.File
  ) extends mill.define.BaseModule(baseModuleInfo.millSourcePath0, foreign0 = foreign0)(
        millModuleEnclosing0,
        millModuleLine0,
        millName0,
        millFile0,
        Caller(())
      ) with mill.main.MainModule {

    override implicit lazy val millDiscover = Discover[this.type]
  }
}
