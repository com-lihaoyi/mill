package mill.testkit

/**
 * A wrapper of [[mill.define.BaseModule]] meant for easy instantiation in test suites.
 */
abstract class TestBaseModule(
    baseModuleSourcePath: os.Path
)(implicit
    millModuleEnclosing0: sourcecode.Enclosing,
    millModuleLine0: sourcecode.Line,
    millModuleFile0: sourcecode.File
) extends mill.define.BaseModule(millSourcePath0 = baseModuleSourcePath)(
      millModuleEnclosing0,
      millModuleLine0,
      millModuleFile0
    ) {
  def this()(implicit
      millModuleEnclosing0: sourcecode.Enclosing,
      millModuleLine0: sourcecode.Line,
      millModuleFile0: sourcecode.File
  ) = this({
    os.makeDir.all(os.pwd / "out/mill-test-base-module")
    os.temp.dir(os.pwd / "out/mill-test-base-module", deleteOnExit = false)
  })
}
