package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest.*

object HeaderMetaBuildMvnDepsTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    // A `//| mvnDeps` header in `mill-build/build.mill` should provide dependencies
    // for compiling `mill-build/build.mill` itself, just like a header in the root
    // `build.mill` does for the root build file
    test("test") - integrationTest { tester =>
      import tester.*

      val metaMvnDeps = eval(("--meta-level", "2", "show", "mvnDeps"))
      assert(metaMvnDeps.isSuccess)
      assert(metaMvnDeps.out.contains("com.lihaoyi::scalatags:0.13.1"))

      val result = eval(("show", "hello"))
      assert(result.isSuccess)
      assert(result.out == "\"<div>hello</div>\"")
    }
  }
}
