package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest._

object YamlScriptTests extends UtestIntegrationTestSuite {
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
          "InvalidTaskType.java:mvnDeps Failed de-serializing config override: expected sequence got string"
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
        assert(res.err.replace('\\', '/').contains(
          "invalid build config in `invalid-key/Foo.java`: key \"moduleDep\" does not override any task"
        ))
        // make sure we truncate the exception to the relevant bits
        assert(res.err.linesIterator.toList.length < 20)

        val res2 = tester.eval("invalid-key/Foo.scala")
        assert(res2.err.replace('\\', '/').contains(
          "invalid build config in `invalid-key/Foo.scala`: key \"moduleDep\" does not override any task"
        ))
        assert(res2.err.linesIterator.toList.length < 20)

        val res3 = tester.eval("invalid-key/Foo.kt")
        assert(res3.err.replace('\\', '/').contains(
          "invalid build config in `invalid-key/Foo.kt`: key \"moduleDep\" does not override any task"
        ))
        assert(res3.err.linesIterator.toList.length < 20)
      }

      locally {
        val res = tester.eval("InvalidPosition.java")
        assert(res.err.contains(
          "Invalid YAML header comment at InvalidPosition.java:1: //| extends: [doesntExist]"
        ))
        // make sure we truncate the exception to the relevant bits
        assert(res.err.linesIterator.toList.length < 20)
      }
      locally {
        val res = tester.eval("InvalidJvmVersion.java")
        assert(res.err.contains(
          "invalid build config in `InvalidJvmVersion.java`: key \"mill-jvm-version\" does not override any task"
        ))
        // make sure we truncate the exception to the relevant bits
        assert(res.err.linesIterator.toList.length < 20)
      }
    }
  }
}
