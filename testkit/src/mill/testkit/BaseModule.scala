package mill.testkit

import mill.define.{Caller, Discover}

/**
 * A wrapper of [[mill.define.BaseModule]] meant for easy instantiation in test suites.
 */
class BaseModule(implicit
    millModuleEnclosing0: sourcecode.Enclosing,
    millModuleLine0: sourcecode.Line,
    millModuleFile0: sourcecode.File
) extends mill.define.BaseModule(
      MillTestKit.getSrcPathBase() / millModuleEnclosing0.value.split("\\.| |#")
    )(
      millModuleEnclosing0,
      millModuleLine0,
      millModuleFile0,
      Caller(null)
    ) {
  lazy val millDiscover: Discover[this.type] = Discover[this.type]
}
