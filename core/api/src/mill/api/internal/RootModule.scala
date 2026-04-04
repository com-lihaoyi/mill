package mill.api.internal

import mill.api.daemon.internal.{RootModuleApi, internal}
import mill.api.Discover

/**
 * Used to mark a module in your `build.mill` as a top-level module, so it's
 * tasks can be run directly e.g. via `mill run` rather than
 * prefixed by the module name `mill foo.run`.
 *
 * Only one top-level module may be defined in your `build.mill`, and it must be
 * defined at the top level of the `build.mill` and not nested in any other
 * modules.
 */
abstract class RootModule()(using
    baseModuleInfo: RootModule.Info,
    millModuleEnclosing0: sourcecode.Enclosing,
    millModuleLine0: sourcecode.Line,
    millFile0: sourcecode.File
) extends RootModule0(baseModuleInfo.projectRoot)
    with RootModuleApi {

  // Dummy `millDiscover` defined but never actually used and overridden by codegen.
  // Provided for IDEs to think that one is available and not show errors in
  // build.mill/package.mill even though they can't see the codegen
  def millDiscover: Discover = sys.error("RootModule#millDiscover must be overridden")
}

@internal
object RootModule {
  class Info(
      val projectRoot: os.Path,
      val output: os.Path,
      val topLevelProjectRoot: os.Path
  ) {
    // bincompat stub
    def this(projectRoot0: String, output0: String, topLevelProjectRoot0: String) =
      this(os.Path(projectRoot0), os.Path(output0), os.Path(topLevelProjectRoot0))

    // bincompat stub
    def millMiscInfo: Info = this
  }

  object Info {
    // Each run classloader has its own copy of this object (since mill.api.internal
    // is not in the shared prefixes), so this lazy val is effectively per-classloader.
    // The resource is written by MillBuildBootstrap before the classloader is created.
    private lazy val cached: Info = {
      val stream = classOf[Info].getClassLoader.getResourceAsStream("mill/rootModuleInfo.json")
      if (stream != null) {
        val json = ujson.read(stream)
        stream.close()
        new Info(
          os.Path(json("projectRoot").str),
          os.Path(json("output").str),
          os.Path(json("topLevelProjectRoot").str)
        )
      } else {
        // Dummy build fallback: read from environment variables
        val wsRoot = sys.env.get(mill.constants.EnvVars.MILL_WORKSPACE_ROOT)
          .fold(os.pwd)(os.Path(_))
        val output = sys.env.get(mill.constants.EnvVars.MILL_OUTPUT_DIR)
          .fold(wsRoot / "out")(os.Path(_))
        new Info(wsRoot, output, wsRoot)
      }
    }

    implicit def info: Info = cached

    // bincompat stub
    def dummyInfo: Info = info

    // bincompat stub
    class FromEnv extends Info(
      sys.env.get(mill.constants.EnvVars.MILL_WORKSPACE_ROOT).fold(os.pwd)(os.Path(_)),
      sys.env.get(mill.constants.EnvVars.MILL_OUTPUT_DIR).fold(
        sys.env.get(mill.constants.EnvVars.MILL_WORKSPACE_ROOT).fold(os.pwd)(os.Path(_)) / "out"
      )(os.Path(_)),
      sys.env.get(mill.constants.EnvVars.MILL_WORKSPACE_ROOT).fold(os.pwd)(os.Path(_))
    )
  }
}
