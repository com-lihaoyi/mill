package mill.exec

import mill.util.Jvm
import mill.api.Ctx.Dest
import mill.testkit.UnitTester
import mill.testkit.TestBaseModule

import mill.util.JarManifest

import utest.*
import mill.*
import mill.define.{Discover, Task}

object JavaCompileJarTests extends TestSuite {
  def compileAll(sources: Seq[mill.api.PathRef])(implicit ctx: Dest) = {
    os.makeDir.all(ctx.dest)

    os.proc("javac", sources.map(_.path.toString()).toSeq, "-d", ctx.dest).call(ctx.dest)
    mill.api.PathRef(ctx.dest)
  }

  val resourceFolder = os.Path(sys.env("MILL_TEST_RESOURCE_DIR"))
  val javacSrcPath = resourceFolder / "examples/javac"

  def noFoos(s: String) = !s.contains("Foo")
  val tests = Tests {

    test("javac") {
      object Build extends TestBaseModule {
        def sourceRootPath: os.SubPath = "src"
        def readmePath: os.SubPath = "readme.md"
        def resourceRootPath: os.SubPath = "resources"

        // sourceRoot -> allSources -> classFiles
        //                                |
        //                                v
        //           resourceRoot ---->  jar
        //                                ^
        //           readmePath---------- |
        def readme = Task.Source { readmePath }
        def sourceRoot = Task.Sources { sourceRootPath }
        def resourceRoot = Task.Sources { resourceRootPath }
        def allSources =
          Task { sourceRoot().flatMap(p => os.walk(p.path)).map(mill.api.PathRef(_)) }
        def classFiles = Task { compileAll(allSources()) }
        def jar = Task {
          val jar = Jvm.createJar(
            Task.dest / "out.jar",
            Seq(classFiles().path, readme().path) ++ resourceRoot().map(_.path)
          )
          PathRef(jar)
        }
        // Test createJar() with optional file filter.
        def filterJar = Task {
          val jar = Jvm.createJar(
            Task.dest / "out.jar",
            Seq(classFiles().path, readme().path) ++ resourceRoot().map(_.path),
            JarManifest.MillDefault,
            (p: os.Path, r: os.RelPath) => noFoos(r.last)
          )
          PathRef(jar)
        }

        def run(mainClsName: String) = Task.Command {
          os.proc("java", "-Duser.language=en", "-cp", classFiles().path, mainClsName)
            .call(stderr = os.Pipe)
        }

        lazy val millDiscover = Discover[this.type]
      }

      import Build._

      var evaluator = UnitTester(
        Build,
        sourceRoot = javacSrcPath
      )
      def eval[T](t: Task[T]) = evaluator.apply(t)
      def check(targets: Seq[Task[?]], expected: Seq[Task[?]]) = evaluator.check(targets, expected)

      def append(path: os.SubPath, txt: String) = os.write.append(moduleDir / path, txt)

      check(
        targets = Seq(jar),
        expected = Seq(allSources, classFiles, jar)
      )

      // Re-running with no changes results in nothing being evaluated
      check(targets = Seq(jar), expected = Seq())
      // Appending an empty string gets ignored due to file-content hashing
      append(sourceRootPath / "Foo.java", "")
      check(targets = Seq(jar), expected = Seq())

      // Appending whitespace forces a recompile, but the classfiles end up
      // exactly the same so no re-jarring.
      append(sourceRootPath / "Foo.java", " ")
      // Note that `sourceRoot` and `resourceRoot` never turn up in the `expected`
      // list, because they are `Source`s not `Target`s
      check(targets = Seq(jar), expected = Seq( /*sourceRoot, */ allSources, classFiles))

      // Appending a new class changes the classfiles, which forces us to
      // re-create the final jar
      append(sourceRootPath / "Foo.java", "\nclass FooTwo{}")
      check(targets = Seq(jar), expected = Seq(allSources, classFiles, jar))

      // Tweaking the resources forces rebuild of the final jar, without
      // recompiling classfiles
      append(resourceRootPath / "hello.txt", " ")
      check(targets = Seq(jar), expected = Seq(jar))

      // Touching the readme.md, defined as `Task.Source`, forces a jar rebuild
      append(readmePath, " ")
      check(targets = Seq(jar), expected = Seq(jar))

      // You can swap evaluators halfway without any ill effects
      evaluator = UnitTester(
        Build,
        sourceRoot = javacSrcPath,
        resetSourcePath = false
      )

      // Asking for an intermediate target forces things to be build up to that
      // target only; these are re-used for any downstream targets requested
      append(sourceRootPath / "Bar.java", "\nclass BarTwo{}")
      append(resourceRootPath / "hello.txt", " ")
      check(targets = Seq(classFiles), expected = Seq(allSources, classFiles))
      check(targets = Seq(jar), expected = Seq(jar))
      check(targets = Seq(allSources), expected = Seq())

      append(sourceRootPath / "Bar.java", "\nclass BarThree{}")
      append(resourceRootPath / "hello.txt", " ")
      check(targets = Seq(resourceRoot), expected = Seq())
      check(targets = Seq(allSources), expected = Seq(allSources))
      check(targets = Seq(jar), expected = Seq(classFiles, jar))

      val jarContents = os.proc("jar", "-tf", evaluator.outPath / "jar.dest/out.jar").call(
        evaluator.outPath
      ).out.text()
      val expectedJarContents =
        """META-INF/MANIFEST.MF
          |META-INF/
          |out/
          |test/
          |out/mill-home/
          |out/mill-workspace/
          |test/Bar.class
          |test/BarThree.class
          |test/BarTwo.class
          |test/Foo.class
          |test/FooTwo.class
          |readme.md
          |hello.txt
          |META-INF/License
          |""".stripMargin
      assert(jarContents.linesIterator.toSeq == expectedJarContents.linesIterator.toSeq)

      // Create the Jar again, but this time, filter out the Foo files.
      eval(filterJar)
      val filteredJarContents = os.proc(
        "jar",
        "-tf",
        evaluator.outPath / "filterJar.dest/out.jar"
      ).call(evaluator.outPath).out.text()
      assert(filteredJarContents.linesIterator.toSeq == expectedJarContents.linesIterator.filter(
        noFoos(_)
      ).toSeq)

      val executed = os.proc(
        "java",
        "-cp",
        evaluator.outPath / "jar.dest/out.jar",
        "test.Foo"
      ).call(evaluator.outPath).out.text()
      assert(executed == s"${31337 + 271828}${System.lineSeparator}")

      for (i <- 0 until 3) {
        // Build.run is not cached, so every time we eval it, it has to
        // re-evaluate
        val Right(result) = eval(Build.run("test.Foo")): @unchecked
        assert(
          result.value.out.text() == s"${31337 + 271828}${System.lineSeparator}",
          result.evalCount == 1
        )
      }

      val Left(mill.api.ExecResult.Exception(ex, _)) = eval(Build.run("test.BarFour")): @unchecked

      assert(ex.getMessage.contains("Could not find or load main class"))

      append(
        sourceRootPath / "Bar.java",
        """
        class BarFour{
          public static void main(String[] args){
            System.out.println("New Cls!");
          }
        }
        """
      )
      val Right(result2) = eval(Build.run("test.BarFour")): @unchecked
      assert(
        result2.value.out.text() == "New Cls!" + System.lineSeparator,
        result2.evalCount == 3
      )
      val Right(result3) = eval(Build.run("test.BarFour")): @unchecked
      assert(
        result3.value.out.text() == "New Cls!" + System.lineSeparator,
        result3.evalCount == 1
      )
    }
  }
}
