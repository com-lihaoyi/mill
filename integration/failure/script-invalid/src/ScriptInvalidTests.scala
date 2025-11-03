package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest._

object ScriptInvalidTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    integrationTest { tester =>
      locally {
        val res = tester.eval("./InvalidExtends.java")
        assert(res.err.contains("Script InvalidExtends.java extends invalid class \"doesntExist\""))
        // make sure we truncate the exception to the relevant bits
        assert(res.err.linesIterator.toList.length < 20)
      }
      locally {
        val res = tester.eval("./InvalidModuleDepType.java")
        assert(res.err.contains(
          "Failed de-serializing config key $['moduleDeps'] in InvalidModuleDepType.java: expected sequence got string"
        ))
        assert(res.err.linesIterator.toList.length < 20)
      }
      locally {
        val res = tester.eval("./ModuleDepResolveError.java")
        assert(res.err.contains("Unable to resolve modules: \"doesntExist\""))
      }
      locally {
        val res = tester.eval("./InvalidTaskType.java")
        assert(res.err.contains(
          "InvalidTaskType.java.mvnDeps Failed de-serializing config override: expected sequence got string"
        ))
        // make sure we truncate the exception to the relevant bits
        assert(res.err.linesIterator.toList.length < 20)
      }
      locally {
        val res = tester.eval("./InvalidYamlSyntax.java")
        assert(res.err.contains(
          "Failed de-serializing build header in InvalidYamlSyntax.java:"
        ))
        // make sure we truncate the exception to the relevant bits
        assert(res.err.linesIterator.toList.length < 30)
      }

      locally {
        val res = tester.eval("invalid-key/Foo.java")
        assert(res.err.contains(
          "invalid build config `invalid-key/Foo.java` key does not override any task: \"moduleDep\""
        ))
        // make sure we truncate the exception to the relevant bits
        assert(res.err.linesIterator.toList.length < 20)

        val res2 = tester.eval("invalid-key/Foo.scala")
        assert(res2.err.contains(
          "invalid build config `invalid-key/Foo.scala` key does not override any task: \"moduleDep\""
        ))
        assert(res2.err.linesIterator.toList.length < 20)

        val res3 = tester.eval("invalid-key/Foo.kt")
        assert(res3.err.contains(
          "invalid build config `invalid-key/Foo.kt` key does not override any task: \"moduleDep\""
        ))
        assert(res3.err.linesIterator.toList.length < 20)
      }

      locally{

      }
    }
  }
}
