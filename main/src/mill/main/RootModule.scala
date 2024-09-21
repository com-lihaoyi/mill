package mill.main

import mill.api.internal
import mill.define.{Caller, Discover}
import scala.annotation.compileTimeOnly

/**
 * Used to mark a module in your `build.mill` as a top-level module, so it's
 * tasks can be run directly e.g. via `mill run` rather than
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
) extends mill.define.BaseModule(baseModuleInfo.projectRoot)(
      millModuleEnclosing0,
      millModuleLine0,
      millFile0,
      Caller(null)
    ) with mill.main.MainModule {

  // Dummy `millDiscover` defined but never actually used and overriden by codegen.
  // Provided for IDEs to think that one is available and not show errors in
  // build.mill/package.mill even though they can't see the codegen
  def millDiscover: Discover = sys.error("RootModule#millDiscover must be overriden")
}

@internal
object RootModule {
  class Info(
      val enclosingClasspath: Seq[os.Path],
      val compilerWorkerClasspath: Seq[os.Path],
      val projectRoot: os.Path,
      val output: os.Path,
      val topLevelProjectRoot: os.Path
  ) {
    def this(
        enclosingClasspath0: Seq[String],
        compilerWorkerClasspath0: Seq[String],
        projectRoot0: String,
        output0: String,
        topLevelProjectRoot0: String
    ) = this(
      enclosingClasspath0.map(os.Path(_)),
      compilerWorkerClasspath0.map(os.Path(_)),
      os.Path(projectRoot0),
      os.Path(output0),
      os.Path(topLevelProjectRoot0)
    )
    implicit val millMiscInfo: Info = this
  }

  object Info {
    // Dummy `RootModule.Info` available in implicit scope but never actually used.
    // as it is provided by the codegen. Defined for IDEs to think that one is available
    // and not show errors in build.mill/package.mill even though they can't see the codegen
    @compileTimeOnly("RootModule can only be instantiated in a build.mill or package.mill file")
    implicit def dummyInfo: Info = sys.error("implicit RootModule.Info must be provided")
  }
}
