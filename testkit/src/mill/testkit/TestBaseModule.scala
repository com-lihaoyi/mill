package mill.testkit

import mill.define.{Caller, Discover}

/**
 * A wrapper of [[mill.define.BaseModule]] meant for easy instantiation in test suites.
 */
class TestBaseModule(implicit
    millModuleEnclosing0: sourcecode.Enclosing,
    millModuleLine0: sourcecode.Line,
    millModuleFile0: sourcecode.File
) extends mill.define.BaseModule(
      os.temp.dir(deleteOnExit = false)
    )(
      millModuleEnclosing0,
      millModuleLine0,
      millModuleFile0,
      Caller(null)
    ) {
  lazy val millDiscover: Discover[this.type] = Discover[this.type]
}
