//// SNIPPET:BUILD1
import mill._, javalib._

object foo extends JavaModule {
  object test extends JavaTests {
    def testFramework = "com.novocode.junit.JUnitFramework"
    def ivyDeps = T {
      super.ivyDeps() ++ Agg(ivy"com.novocode:junit-interface:0.11")
    }
  }
}
// This build defines a single module with a test suite, configured to use
// "JUnit" as the testing framework. Test suites are themselves ``JavaModule``s,
// nested within the enclosing module,
//// SNIPPET:BUILD2

object bar extends JavaModule {
  object test extends JavaTests with TestModule.Junit4
}

//// SNIPPET:BUILD3
object qux extends JavaModule {
  object test extends JavaTests with TestModule.Junit5
  object integration extends JavaTests with TestModule.Junit5
}

// This example also demonstrates using Junit 5 instead of Junit 4,
// with
