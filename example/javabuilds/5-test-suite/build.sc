//// SNIPPET:BUILD1
import mill._, javalib._

object foo extends JavaModule {
  object test extends JavaModuleTests {
    def testFramework = "com.novocode.junit.JUnitFramework"
    def ivyDeps = T {
      super.ivyDeps() ++ Agg(ivy"com.novocode:junit-interface:0.11")
    }
  }
}

//// SNIPPET:BUILD2

object bar extends JavaModule {
  object test extends JavaModuleTests with TestModule.Junit4
}

//// SNIPPET:BUILD3
object qux extends JavaModule {
  object test extends JavaModuleTests with TestModule.Junit4
  object integration extends JavaModuleTests with TestModule.Junit4
}
