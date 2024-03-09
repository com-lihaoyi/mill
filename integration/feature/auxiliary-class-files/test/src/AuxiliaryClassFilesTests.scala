package mill.integration

import utest._

// Regress test for issue https://github.com/com-lihaoyi/mill/issues/1901
object AuxiliaryClassFilesTests extends IntegrationTestSuite {
  val tests: Tests = Tests {
    test("tasty files are deleted together with companion class files") {
      initWorkspace()
      eval("app.jvm.compile")

      val classes = wd / "out" / "app" / "jvm" / "compile.dest" / "classes"
      val firstRun = os.list(classes).map(_.last)

      os.remove(wd / "app" / "src" / "foo.scala")

      eval("app.jvm.compile")

      val secondRun = os.list(classes).map(_.last)

      assert(firstRun == Seq("foo$.class", "foo.class", "foo.tasty"))
      assert(secondRun == Seq.empty)
    }

    test("nir files are deleted together with companion class files") {
      initWorkspace()
      eval("app.native.compile")

      val classes = wd / "out" / "app" / "native" / "compile.dest" / "classes"
      val firstRun = os.list(classes).map(_.last)

      os.remove(wd / "app" / "src" / "foo.scala")

      eval("app.native.compile")

      val secondRun = os.list(classes).map(_.last)

      assert(firstRun == Seq("foo$.class", "foo$.nir", "foo.class", "foo.nir", "foo.tasty"))
      assert(secondRun == Seq.empty)
    }

    test("sjsir files are deleted together with companion class files") {
      initWorkspace()
      eval("app.js.compile")

      val classes = wd / "out" / "app" / "js" / "compile.dest" / "classes"
      val firstRun = os.list(classes).map(_.last)

      os.remove(wd / "app" / "src" / "foo.scala")

      eval("app.js.compile")

      val secondRun = os.list(classes).map(_.last)

      assert(firstRun == Seq("foo$.class", "foo$.sjsir", "foo.class", "foo.tasty"))
      assert(secondRun == Seq.empty)
    }
  }
}
