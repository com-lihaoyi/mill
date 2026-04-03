package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest.*

object YamlScriptTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    integrationTest { tester =>
      locally {
        val res = tester.eval("./InvalidExtends.java")
        res.assertContainsLines(
          "[error] InvalidExtends.java:1:15",
          "//| extends: [doesntExist]",
          "              ^"
        )
        assert(res.err.contains("Script extends invalid class \"doesntExist\""))
        assert(res.err.linesIterator.toList.length < 20)
      }
      locally {
        val res = tester.eval("./InvalidModuleDepType.java")
        res.assertContainsLines(
          "[error] InvalidModuleDepType.java:1:17",
          "//| moduleDeps: dummy",
          "                ^"
        )
        assert(res.err.contains(
          "Failed de-serializing config key $['moduleDeps']: expected sequence got string"
        ))
        assert(res.err.linesIterator.toList.length < 20)
      }
      locally {
        val res = tester.eval("./ModuleDepResolveError.java")
        res.assertContainsLines(
          "[error] ModuleDepResolveError.java:1:18",
          "//| moduleDeps: [doesntExist]",
          "                 ^"
        )
        assert(res.err.contains("Cannot resolve doesntExist"))
      }
      locally {
        val res = tester.eval("./InvalidTaskType.java")
        res.assertContainsLines(
          "[error] InvalidTaskType.java:1:14",
          "//| mvnDeps: dummy",
          "             ^"
        )
        assert(
          res.err.contains("Failed de-serializing config override: expected sequence got string")
        )
        assert(res.err.linesIterator.toList.length < 20)
      }
      locally {
        val res = tester.eval("./InvalidYamlSyntax.java")
        res.assertContainsLines(
          "[error] InvalidYamlSyntax.java:1:14",
          "//| extends: ]",
          "             ^"
        )
        assert(res.err.contains("expected the node content, but found ']'"))
        assert(res.err.linesIterator.toList.length < 30)
      }

      locally {
        val res = tester.eval("invalid-key/Foo.java")
        res.assertContainsLines(
          "[error] invalid-key/Foo.java:1:5",
          "//| moduleDep: [doesntExist]",
          "    ^"
        )
        assert(res.err.contains("key \"moduleDep\" does not override any task"))
        assert(res.err.linesIterator.toList.length < 20)

        val res2 = tester.eval("invalid-key/Foo.scala")
        res2.assertContainsLines(
          "[error] invalid-key/Foo.scala:1:5",
          "//| moduleDep: [doesntExist]",
          "    ^"
        )
        assert(res2.err.contains("key \"moduleDep\" does not override any task"))
        assert(res2.err.linesIterator.toList.length < 20)

        val res3 = tester.eval("invalid-key/Foo.kt")
        res3.assertContainsLines(
          "[error] invalid-key/Foo.kt:1:5",
          "//| moduleDep: [doesntExist]",
          "    ^"
        )
        assert(res3.err.contains("key \"moduleDep\" does not override any task"))
        assert(res3.err.linesIterator.toList.length < 20)
      }

      locally {
        // This error is thrown as RuntimeException from low-level parsing,
        // so the error format includes the exception prefix
        val res = tester.eval("InvalidPosition.java")
        assert(res.err.contains("[error] InvalidPosition.java:2:1"))
        assert(res.err.contains("//| extends: [doesntExist]"))
        assert(res.err.contains("^"))
        assert(res.err.contains("YAML header comments can only occur at the start of the file"))
        assert(res.err.linesIterator.toList.length < 20)
      }
      locally {
        val res = tester.eval("InvalidJvmVersion.java")
        res.assertContainsLines(
          "[error] InvalidJvmVersion.java:1:5",
          "//| mill-jvm-version: 17",
          "    ^"
        )
        assert(res.err.contains(
          "key \"mill-jvm-version\" can only be used in your root `build.mill` or `build.mill.yaml` file"
        ))
        assert(res.err.linesIterator.toList.length < 20)
      }
      locally {
        val res = tester.eval("not-script-valid-file")
        assert(res.err.contains(
          "Script not-script-valid-file has no `extends` clause configured and is of an unknown extension ``"
        ))
        assert(res.err.linesIterator.toList.length < 20)
      }
      locally {
        val res = tester.eval("not-script-not-valid-file.ext")
        assert(res.err.contains(
          "Cannot resolve not-script-not-valid-file.ext. Try `mill resolve _` to see what's available."
        ))
        assert(res.err.linesIterator.toList.length < 20)
      }
      locally {
        val res = tester.eval("Recursive.scala")
        assert(res.err.contains(
          "Recursive moduleDeps detected: Recursive.scala -> Recursive2.scala -> Recursive3.scala -> Recursive.scala"
        ))
        assert(res.err.linesIterator.toList.length < 20)
      }
      locally {
        val res = tester.eval("IndirectFailure0.scala")
        assert(res.err.contains(
          "Cannot resolve DoesntExist.scala. "
        ))
        assert(res.err.linesIterator.toList.length < 20)
      }
    }
  }
}
