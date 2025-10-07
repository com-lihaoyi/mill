package mill.testkit

import mill.api.internal.RootModule0

/**
 * A wrapper of [[RootModule0]] meant for easy instantiation in test suites.
 */
abstract class TestRootModule(
    baseModuleSourcePath: os.Path
)(using
    millModuleEnclosing0: sourcecode.Enclosing,
    millModuleLine0: sourcecode.Line,
    millModuleFile0: sourcecode.File
) extends RootModule0(millSourcePath0 = baseModuleSourcePath)(
      using
      millModuleEnclosing0,
      millModuleLine0,
      millModuleFile0
    ) {
  def this()(using
      millModuleEnclosing0: sourcecode.Enclosing,
      millModuleLine0: sourcecode.Line,
      millModuleFile0: sourcecode.File
  ) = this({
    os.makeDir.all(os.pwd / "out/mill-test-base-module")
    os.temp.dir(os.pwd / "out/mill-test-base-module", deleteOnExit = false)
  })
}
