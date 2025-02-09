package mill.testkit


/**
 * A wrapper of [[mill.define.BaseModule]] meant for easy instantiation in test suites.
 */
abstract class TestBaseModule(implicit
    millModuleEnclosing0: sourcecode.Enclosing,
    millModuleLine0: sourcecode.Line,
    millModuleFile0: sourcecode.File
) extends mill.define.BaseModule(
      {
        os.makeDir.all(os.pwd / "out/mill-test-base-module")
        os.temp.dir(os.pwd / "out/mill-test-base-module", deleteOnExit = false)
      }
    )(
      millModuleEnclosing0,
      millModuleLine0,
      millModuleFile0,
    ) {}
