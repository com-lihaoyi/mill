package mill.integration

import mill.testkit.{IntegrationTester, UtestIntegrationTestSuite}
import utest.*

/**
 * Checks that a built checkout keeps working after it is moved or copied to another folder,
 * together with its `out/` folder.
 */
object RelocatedCheckoutTests extends UtestIntegrationTestSuite {
  // The two checkouts must not share an `out/` folder
  override def allowSharedOutputDir: Boolean = false

  // Copies the files under `from` to `to`, like `cp -Rp`. The daemon and lock files are skipped,
  // since the first run's daemon may still hold them.
  def copyTree(from: os.Path, to: os.Path): Unit = {
    val skip = Set("mill-daemon", "mill-no-daemon", "mill-out-lock")
    for (p <- os.walk(from, skip = p => skip(p.last)) if !os.isDir(p))
      os.copy(p, to / p.relativeTo(from), copyAttributes = true, createFolders = true)
  }

  // Copies the checkout at `from` into `tester`'s workspace
  def copyCheckout(from: os.Path, tester: IntegrationTester): Unit = {
    os.remove.all(tester.workspacePath)
    copyTree(from, tester.workspacePath)
  }

  // Moves the checkout at `from` into `tester`'s workspace, like `mv`: it is copied, then the
  // original build and sources are deleted
  def moveCheckout(from: os.Path, tester: IntegrationTester): Unit = {
    copyCheckout(from, tester)
    for (p <- os.list(from) if p.last != "out") os.remove.all(p)
  }

  // Replaces the test project with `build` and `files`, and compiles it
  def compileNewBuild(tester: IntegrationTester, build: String, files: (String, String)*) = {
    val ws = tester.workspacePath
    os.remove(ws / "build.mill.yaml")
    os.remove.all(ws / "src")
    os.write(ws / "build.mill", "package build\n" + build)
    for ((path, text) <- files) os.write(ws / os.SubPath(path), text, createFolders = true)
    tester.eval("__.compile")
  }

  // Builds a checkout, moves it, edits a source in the new folder and checks that the edited
  // code runs
  def checkMove(
      setup: os.Path => Unit = _ => (),
      afterMove: IntegrationTester => Unit = _ => ()
  ): Unit = {
    val first = integrationTest { tester =>
      setup(tester.workspacePath)
      assert(tester.eval("run").out.contains("hello"))
      tester.workspacePath
    }
    integrationTest { tester =>
      moveCheckout(first, tester)
      tester.modifyFile(
        tester.workspacePath / "src/app/App.scala",
        _.replace("\"hello\"", "\"moved\"")
      )
      val res = tester.eval("run")
      assert(res.isSuccess)
      assert(res.out.contains("moved"))
      afterMove(tester)
    }
  }

  val tests: Tests = Tests {
    // A YAML build looks up its settings by the build file's path
    test("yamlBuild") - checkMove()
    // `build.mill` code sees its own file name through `sourcecode.File`
    test("millBuild") - checkMove(
      setup = ws => {
        os.remove(ws / "build.mill.yaml")
        os.write(
          ws / "build.mill",
          """package build
            |import mill.*, scalalib.*
            |object `package` extends ScalaModule {
            |  def scalaVersion = "3.8.2"
            |  def buildFile = Task.Input { summon[sourcecode.File].value }
            |}
            |""".stripMargin
        )
      },
      afterMove = tester => {
        // `buildFile` is an input, so this checks the compiled build code, not a cached value
        val res = tester.eval(("show", "buildFile"))
        assert(res.isSuccess)
        assert(os.Path(ujson.read(res.out).str) == tester.workspacePath / "build.mill")
      }
    )
    // Zinc's file ids are relative to the checkout, so a copy keeps compiling incrementally,
    // and a compile there never touches the original's class files
    test("copiedCheckout") {
      val kotlinVersion = sys.env("TEST_KOTLIN_VERSION")
      val first = integrationTest { tester =>
        val res = compileNewBuild(
          tester,
          s"""import mill.*, javalib.*, kotlinlib.*
             |object foo extends JavaModule
             |object kt extends KotlinModule { def kotlinVersion = "$kotlinVersion" }""".stripMargin,
          Seq(
            "foo/src/A.java" -> "public class A { public static int f() { return 1; } }",
            "foo/src/B.java" -> "public class B { int g() { return A.f(); } }",
            "foo/src/Gone.java" -> "public class Gone {}",
            "kt/src/Kt.kt" -> "object Kt { fun hi() = 1 }",
            "kt/src/J.java" -> "class J { int hi() { return Kt.INSTANCE.hi(); } }"
          ) ++
            // Unrelated sources, so the edits below stay under Zinc's "recompile everything when
            // more than half the sources changed" threshold
            Seq("C", "D", "E").map(c => s"foo/src/$c.java" -> s"public class $c {}")*
        )
        assert(res.isSuccess)
        tester.workspacePath
      }
      integrationTest { tester =>
        copyCheckout(first, tester)
        val ws = tester.workspacePath
        val classes = ws / "out/foo/compile.dest/classes"
        val cClassTime = os.mtime(classes / "C.class")
        Thread.sleep(1100) // coarse file timestamps on some file systems
        tester.modifyFile(ws / "foo/src/A.java", _ + "\n")
        os.remove(ws / "foo/src/Gone.java")
        tester.modifyFile(ws / "kt/src/J.java", _ + "\n")
        assert(tester.eval("__.compile").isSuccess)

        // Only the edited source was recompiled, and the deleted one's class file removed
        assert(os.mtime(classes / "C.class") == cClassTime)
        assert(!os.exists(classes / "Gone.class"))
        // Kotlin's classes share the output folder with Java's
        assert(os.exists(ws / "out/kt/compile.dest/classes/Kt.class"))
        // The original checkout is untouched
        for (c <- Seq("A", "B", "Gone"))
          assert(os.exists(first / "out/foo/compile.dest/classes" / s"$c.class"))
        assert(os.exists(first / "out/kt/compile.dest/classes/J.class"))

        // Later compiles still see what depends on what
        tester.modifyFile(ws / "foo/src/A.java", _.replace("f()", "h()"))
        assert(!tester.eval("foo.compile").isSuccess)
      }
    }
    // Only `out/` copied and used with `MILL_OUTPUT_DIR`, as in the relocation check of
    // https://github.com/com-lihaoyi/mill/pull/7725: a compile there never touches `out/`
    test("copiedOutFolder") - integrationTest { tester =>
      val ws = tester.workspacePath
      val res = compileNewBuild(
        tester,
        "import mill.*, javalib.*\nobject foo extends JavaModule",
        "foo/src/A.java" -> "public class A {}",
        "foo/src/B.java" -> "public class B {}"
      )
      assert(res.isSuccess)
      copyTree(ws / "out", ws / "out2")
      tester.modifyFile(ws / "foo/src/A.java", _ + "\n")
      assert(tester.eval("foo.compile", env = Map("MILL_OUTPUT_DIR" -> "out2")).isSuccess)
      for (c <- Seq("A", "B"))
        assert(os.exists(ws / "out/foo/compile.dest/classes" / s"$c.class"))
    }
    // Compiler messages in a copy name the copy's files, also those replayed from the analysis
    test("copiedCheckoutMessages") {
      val scalaVersion = sys.env("TEST_SCALA_2_13_VERSION")
      val first = integrationTest { tester =>
        val res = compileNewBuild(
          tester,
          s"""import mill.*, scalalib.*
             |object foo extends ScalaModule {
             |  def scalaVersion = "$scalaVersion"
             |  def scalacOptions = Seq("-deprecation")
             |  override def zincReportCachedProblems = Task.Input(true)
             |}""".stripMargin,
          Seq(
            "foo/src/Old.scala" -> "@deprecated(\"x\", \"1\") class Old",
            "foo/src/U.scala" -> "class U { def o: Old = null }"
          ) ++ Seq("X", "Y", "Z").map(c => s"foo/src/$c.scala" -> s"class $c")*
        )
        // Shown once, though also replayed from the analysis
        assert("one warning found".r.findAllIn(res.err).size == 1)
        tester.workspacePath
      }
      integrationTest { tester =>
        copyCheckout(first, tester)
        val ws = tester.workspacePath
        // An unrelated edit: U.scala isn't recompiled, its warning comes from the analysis
        val uClass = ws / "out/foo/compile.dest/classes/U.class"
        val uClassTime = os.mtime(uClass)
        Thread.sleep(1100) // coarse file timestamps on some file systems
        tester.modifyFile(ws / "foo/src/X.scala", _ + "\n")
        val res = tester.eval("foo.compile")
        assert(res.isSuccess)
        assert(os.mtime(uClass) == uClassTime)
        assert(res.err.contains("[warn] foo/src/U.scala:1:18"))

        tester.modifyFile(ws / "foo/src/Y.scala", _ => "class Y { val i: Int = \"\" }")
        val failed = tester.eval("foo.compile")
        assert(failed.err.contains("[error] foo/src/Y.scala:1:24"))
        for (out <- Seq(res.err, failed.err))
          assert(!out.contains("${") && !out.contains(first.toString))
      }
    }
  }
}
