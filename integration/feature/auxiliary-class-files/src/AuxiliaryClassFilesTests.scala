package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest.*

// Regress test for issue https://github.com/com-lihaoyi/mill/issues/1901
object AuxiliaryClassFilesTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test("tasty files are deleted together with companion class files") - integrationTest {
      tester =>
        import tester.*
        assert(eval("app.jvm.compile").isSuccess)

        val classes = workspacePath / "out/app/jvm/compile.dest/classes"
        val firstRun = os.list(classes).map(_.last)

        os.remove(workspacePath / "app/src/foo.scala")

        assert(eval("app.jvm.compile").isSuccess)

        val secondRun = os.list(classes).map(_.last)

        assert(firstRun == Seq("foo$.class", "foo.class", "foo.tasty"))
        assert(secondRun == Seq.empty)
    }

    test("compilation fails when deleting a class used by other files") - integrationTest {
      tester =>
        import tester.*
        os.write(workspacePath / "app/src/bar.scala", "object bar { println(foo) }")
        val firstRunSuccessful = eval("app.jvm.compile")
        assert(firstRunSuccessful.isSuccess)

        val classes = workspacePath / "out/app/jvm/compile.dest/classes"
        val firstRun = os.list(classes).map(_.last)

        os.remove(workspacePath / "app/src/foo.scala")

        val secondRunSuccessful = eval("app.jvm.compile")
        assert(!secondRunSuccessful.isSuccess)

        val secondRun = os.list(classes).map(_.last)

        assert(firstRun == Seq(
          "bar$.class",
          "bar.class",
          "bar.tasty",
          "foo$.class",
          "foo.class",
          "foo.tasty"
        ))
        assert(secondRun == Seq.empty)
    }

    test("nir files are deleted together with companion class files") - integrationTest { tester =>
      import tester.*
      assert(eval("app.native.compile").isSuccess)

      val classes = workspacePath / "out/app/native/compile.dest/classes"
      val firstRun = os.list(classes).map(_.last)

      os.remove(workspacePath / "app/src/foo.scala")

      assert(eval("app.native.compile").isSuccess)

      val secondRun = os.list(classes).map(_.last)

      assert(firstRun == Seq("foo$.class", "foo$.nir", "foo.class", "foo.nir", "foo.tasty"))
      assert(secondRun == Seq.empty)
    }

    test("sjsir files are deleted together with companion class files") - integrationTest {
      tester =>
        import tester.*
        assert(eval("app.js.compile").isSuccess)

        val classes = workspacePath / "out/app/js/compile.dest/classes"
        val firstRun = os.list(classes).map(_.last)

        os.remove(workspacePath / "app/src/foo.scala")

        assert(eval("app.js.compile").isSuccess)

        val secondRun = os.list(classes).map(_.last)

        assert(firstRun == Seq("foo$.class", "foo$.sjsir", "foo.class", "foo.tasty"))
        assert(secondRun == Seq.empty)
    }
  }
}
