package mill.integration

import mill.testkit.{IntegrationTester, UtestIntegrationTestSuite}

import utest.*

object CodeSigHelloTests extends UtestIntegrationTestSuite {
  def invalidationTree(tester: IntegrationTester): Seq[String] =
    os.read.lines(tester.workspacePath / "out/mill-invalidation-tree.json")

  val tests: Tests = Tests {
    test("simple") - integrationTest { tester =>
      import tester.*
      // Make sure the simplest case where we have a single task calling a single helper
      // method is properly invalidated when either the task body, or the helper method's body
      // is changed, or something changed in the constructor
      val initial = eval("foo")

      assert(initial.out.linesIterator.toList == Seq("running foo", "running helperFoo"))
      // First run - foo was executed
      assertGoldenLiteral(
        invalidationTree(tester),
        Seq("{", "  \"foo\": {}", "}")
      )

      val cached = eval("foo")
      assert(cached.out == "")
      // Cached - nothing was invalidated
      assertGoldenLiteral(invalidationTree(tester), Seq("{}"))

      modifyFile(workspacePath / "build.mill", _.replace("running foo", "running foo2"))
      val mangledFoo = eval("foo")

      val out1 = mangledFoo.out.linesIterator.toList
      assert(out1 == Seq("running foo2", "running helperFoo"))
      // Code change in foo task body - should show code change path
      assertGoldenLiteral(
        invalidationTree(tester),
        Seq(
          "{",
          "  \"def build_.package_#foo$$anonfun$1$$anonfun$1(scala.collection.immutable.Seq,mill.api.TaskCtx)mill.api.daemon.Result\": {",
          "    \"call build_.package_!foo$$anonfun$1$$anonfun$1(scala.collection.immutable.Seq,mill.api.TaskCtx)mill.api.daemon.Result\": {",
          "      \"def build_.package_#foo$$anonfun$1()mill.api.Task$Simple\": {",
          "        \"call build_.package_!foo$$anonfun$1()mill.api.Task$Simple\": {",
          "          \"def build_.package_#foo()mill.api.Task$Simple\": {",
          "            \"foo\": {}",
          "          }",
          "        }",
          "      }",
          "    }",
          "  }",
          "}"
      ))

      val cached2 = eval("foo")
      assert(cached2.out == "")
      assertGoldenLiteral(invalidationTree(tester), Seq("{}"))

      modifyFile(workspacePath / "build.mill", _.replace("running helperFoo", "running helperFoo2"))
      val mangledHelperFoo = eval("foo")

      assert(mangledHelperFoo.out.linesIterator.toList == Seq("running foo2", "running helperFoo2"))
      // Code change in helperFoo - should show code change path through helperFoo
      assertGoldenLiteral(
        invalidationTree(tester),
        Seq(
          "{",
          "  \"def build_.package_#helperFoo()int\": {",
          "    \"call build_.package_#helperFoo()int\": {",
          "      \"def build_.package_#foo$$anonfun$1$$anonfun$1(scala.collection.immutable.Seq,mill.api.TaskCtx)mill.api.daemon.Result\": {",
          "        \"call build_.package_!foo$$anonfun$1$$anonfun$1(scala.collection.immutable.Seq,mill.api.TaskCtx)mill.api.daemon.Result\": {",
          "          \"def build_.package_#foo$$anonfun$1()mill.api.Task$Simple\": {",
          "            \"call build_.package_!foo$$anonfun$1()mill.api.Task$Simple\": {",
          "              \"def build_.package_#foo()mill.api.Task$Simple\": {",
          "                \"foo\": {}",
          "              }",
          "            }",
          "          }",
          "        }",
          "      }",
          "    }",
          "  }",
          "}"
        )
      )

      // Make sure changing `val`s, which only affects the Module constructor and
      // not the Task method itself, causes invalidation
      modifyFile(workspacePath / "build.mill", _.replace("val valueFoo = 0", "val valueFoo = 10"))
      val mangledValFoo = eval("foo")
      assert(mangledValFoo.out.linesIterator.toList == Seq("running foo2", "running helperFoo2"))
      // Constructor change - should show code change path
      assertGoldenLiteral(
        invalidationTree(tester),
        Seq(
          "{",
          "  \"def build_.package_#<init>()void\": {",
          "    \"call build_.package_!<init>()void\": {",
          "      \"def build_.package_$#<init>()void\": {",
          "        \"foo\": {}",
          "      }",
          "    }",
          "  }",
          "}"
        )
      )

      // Even modifying `val`s that do not affect the task invalidates it, because
      // we only know that the constructor changed and don't do enough analysis to
      // know that this particular val is not used
      modifyFile(
        workspacePath / "build.mill",
        _.replace("val valueFooUsedInBar = 0", "val valueFooUsedInBar = 10")
      )
      val mangledValFooUsedInBar = eval("foo")
      assert(mangledValFooUsedInBar.out.linesIterator.toList == Seq(
        "running foo2",
        "running helperFoo2"
      ))
      // Constructor change - should show code change path
      assertGoldenLiteral(
        invalidationTree(tester),
        Seq(
          "{",
          "  \"def build_.package_#<init>()void\": {",
          "    \"call build_.package_!<init>()void\": {",
          "      \"def build_.package_$#<init>()void\": {",
          "        \"foo\": {}",
          "      }",
          "    }",
          "  }",
          "}"
        )
      )

      val cached3 = eval("foo")
      assert(cached3.out == "")
      assertGoldenLiteral(invalidationTree(tester), Seq("{}"))

      // Changing the body of a `lazy val` invalidates the usages
      modifyFile(
        workspacePath / "build.mill",
        _.replace("lazy val lazyValue2 = 0", "lazy val lazyValue3 = 0")
      )
      modifyFile(
        workspacePath / "build.mill",
        _.replace("lazy val lazyValue = 0", "lazy val lazyValue2 = 0")
      )
      modifyFile(
        workspacePath / "build.mill",
        _.replace("lazy val lazyValue3 = 0", "lazy val lazyValue = 0")
      )
      val cached4 = eval("foo")
      assert(cached4.out == "")
      // Renaming lazy vals without changing their values - should be cached
      assertGoldenLiteral(invalidationTree(tester), Seq("{}"))

      // Changing the body of a `lazy val` invalidates the usages
      modifyFile(
        workspacePath / "build.mill",
        _.replace("lazy val lazyValue = 0", "lazy val lazyValue = 1")
      )
      val mangledLazyVal = eval("foo")
      assert(mangledLazyVal.out.linesIterator.toList == Seq(
        "running foo2",
        "running helperFoo2"
      ))
      // Lazy val value change - should show code change path through lazyValue
      assertGoldenLiteral(
        invalidationTree(tester),
        Seq(
          "{",
          "  \"def build_.package_#lazyValue$lzyINIT1()java.lang.Object\": {",
          "    \"call build_.package_!lazyValue$lzyINIT1()java.lang.Object\": {",
          "      \"def build_.package_#lazyValue()int\": {",
          "        \"call build_.package_#lazyValue()int\": {",
          "          \"def build_.package_#foo$$anonfun$1$$anonfun$1(scala.collection.immutable.Seq,mill.api.TaskCtx)mill.api.daemon.Result\": {",
          "            \"call build_.package_!foo$$anonfun$1$$anonfun$1(scala.collection.immutable.Seq,mill.api.TaskCtx)mill.api.daemon.Result\": {",
          "              \"def build_.package_#foo$$anonfun$1()mill.api.Task$Simple\": {",
          "                \"call build_.package_!foo$$anonfun$1()mill.api.Task$Simple\": {",
          "                  \"def build_.package_#foo()mill.api.Task$Simple\": {",
          "                    \"foo\": {}",
          "                  }",
          "                }",
          "              }",
          "            }",
          "          }",
          "        }",
          "      }",
          "    }",
          "  }",
          "}"
        )
      )
    }
  }
}
