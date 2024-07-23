//// SNIPPET:BUILD

import mill._, javalib._

object qux extends JavaModule {
  def moduleDeps = Seq(baz)

  object test extends JavaTests with TestModule.Junit4 {
    def moduleDeps = super.moduleDeps ++ Seq(baz.test)
  }
}


object baz extends JavaModule {
  object test extends JavaTests with TestModule.Junit4
}
