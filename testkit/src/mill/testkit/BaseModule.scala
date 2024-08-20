package mill.testkit

import mill.define.{Caller, Discover}
class BaseModule(implicit
    millModuleEnclosing0: sourcecode.Enclosing,
    millModuleLine0: sourcecode.Line
) extends mill.define.BaseModule(
      MillTestKit.getSrcPathBase() / millModuleEnclosing0.value.split("\\.| |#")
    )(
      implicitly,
      implicitly,
      implicitly,
      Caller(null)
    ) {
  lazy val millDiscover: Discover[this.type] = Discover[this.type]
}
