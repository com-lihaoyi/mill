package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest._

object YamlScriptTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    integrationTest { tester =>
      locally {
        // //| extends: [doesntExist]
        //               ^ col 15
        val res = tester.eval("./InvalidExtends.java")
        assert(res.err.contains("InvalidExtends.java:1:15"))
        assert(res.err.contains("//| extends: [doesntExist]"))
        assert(res.err.contains("              ^"))
        assert(res.err.contains("Script extends invalid class \"doesntExist\""))
        // make sure we truncate the exception to the relevant bits
        assert(res.err.linesIterator.toList.length < 20)
      }
      locally {
        // //| moduleDeps: dummy
        //                  ^ col 17
        val res = tester.eval("./InvalidModuleDepType.java")
        assert(res.err.contains("InvalidModuleDepType.java:1:17"))
        assert(res.err.contains("//| moduleDeps: dummy"))
        assert(res.err.contains("                ^"))
        assert(res.err.contains(
          "Failed de-serializing config key $['moduleDeps']: expected sequence got string"
        ))
        assert(res.err.linesIterator.toList.length < 20)
      }
      locally {
        // //| moduleDeps: [doesntExist]
        //                  ^ col 18
        val res = tester.eval("./ModuleDepResolveError.java")
        assert(res.err.contains("ModuleDepResolveError.java:1:18"))
        assert(res.err.contains("//| moduleDeps: [doesntExist]"))
        assert(res.err.contains("                 ^"))
        assert(res.err.contains("Unable to resolve module \"doesntExist\""))
      }
      locally {
        // //| mvnDeps: dummy
        //              ^ col 14
        val res = tester.eval("./InvalidTaskType.java")
        assert(res.err.contains("InvalidTaskType.java:1:14"))
        assert(res.err.contains("//| mvnDeps: dummy"))
        assert(res.err.contains("             ^"))
        assert(res.err.contains("Failed de-serializing config override: expected sequence got string"))
        // make sure we truncate the exception to the relevant bits
        assert(res.err.linesIterator.toList.length < 20)
      }
      locally {
        // //| extends: ]
        //              ^ col 14
        val res = tester.eval("./InvalidYamlSyntax.java")
        assert(res.err.contains("InvalidYamlSyntax.java:1:14"))
        assert(res.err.contains("//| extends: ]"))
        assert(res.err.contains("             ^"))
        assert(res.err.contains("expected the node content, but found ']'"))
        // make sure we truncate the exception to the relevant bits
        assert(res.err.linesIterator.toList.length < 30)
      }

      locally {
        // //| moduleDep: [doesntExist]
        //                ^ col 16
        val res = tester.eval("invalid-key/Foo.java")
        assert(res.err.replace('\\', '/').contains("invalid-key/Foo.java:1:16"))
        assert(res.err.contains("//| moduleDep: [doesntExist]"))
        assert(res.err.contains("               ^"))
        assert(res.err.contains("key \"moduleDep\" does not override any task"))
        // make sure we truncate the exception to the relevant bits
        assert(res.err.linesIterator.toList.length < 20)

        val res2 = tester.eval("invalid-key/Foo.scala")
        assert(res2.err.replace('\\', '/').contains("invalid-key/Foo.scala:1:16"))
        assert(res2.err.contains("//| moduleDep: [doesntExist]"))
        assert(res2.err.contains("               ^"))
        assert(res2.err.contains("key \"moduleDep\" does not override any task"))
        assert(res2.err.linesIterator.toList.length < 20)

        val res3 = tester.eval("invalid-key/Foo.kt")
        assert(res3.err.replace('\\', '/').contains("invalid-key/Foo.kt:1:16"))
        assert(res3.err.contains("//| moduleDep: [doesntExist]"))
        assert(res3.err.contains("               ^"))
        assert(res3.err.contains("key \"moduleDep\" does not override any task"))
        assert(res3.err.linesIterator.toList.length < 20)
      }

      locally {
        // Line 2: //| extends: [doesntExist]
        val res = tester.eval("InvalidPosition.java")
        assert(res.err.contains("InvalidPosition.java:2:1"))
        assert(res.err.contains("//| extends: [doesntExist]"))
        assert(res.err.contains("^"))
        assert(res.err.contains("YAML header comments can only occur at the start of the file"))
        // make sure we truncate the exception to the relevant bits
        assert(res.err.linesIterator.toList.length < 20)
      }
      locally {
        // //| mill-jvm-version: 17
        //                       ^ col 23
        val res = tester.eval("InvalidJvmVersion.java")
        assert(res.err.contains("InvalidJvmVersion.java:1:23"))
        assert(res.err.contains("//| mill-jvm-version: 17"))
        assert(res.err.contains("                      ^"))
        assert(res.err.contains("key \"mill-jvm-version\" can only be used in your root `build.mill` or `build.mill.yaml` file"))
        // make sure we truncate the exception to the relevant bits
        assert(res.err.linesIterator.toList.length < 20)
      }
    }
  }
}
