package mill.eval

import ammonite.ops.ImplicitWd._
import ammonite.ops._
import mill.define.{Discover, Input, Target, Task}
import mill.modules.Jvm
import mill.util.Ctx.Dest
import mill.{Module, T}
import mill.util.{DummyLogger, Loose, TestEvaluator, TestUtil}
import mill.util.Strict.Agg
import utest._
import mill._
object JavaCompileJarTests extends TestSuite{
  def compileAll(sources: Seq[PathRef])(implicit ctx: Dest) = {
    mkdir(ctx.dest)
    import ammonite.ops._
    %("javac", sources.map(_.path.toString()), "-d", ctx.dest)(wd = ctx.dest)
    PathRef(ctx.dest)
  }

  val tests = Tests{
    'javac {
      val javacSrcPath = pwd / 'main / 'test / 'resources / 'examples / 'javac
      val javacDestPath =  TestUtil.getOutPath() / 'src

      mkdir(javacDestPath / up)
      cp(javacSrcPath, javacDestPath)

      object Build extends TestUtil.BaseModule{
        def sourceRootPath = javacDestPath / 'src
        def resourceRootPath = javacDestPath / 'resources

        // sourceRoot -> allSources -> classFiles
        //                                |
        //                                v
        //           resourceRoot ---->  jar
        def sourceRoot = T.sources{ sourceRootPath }
        def resourceRoot = T.sources{ resourceRootPath }
        def allSources = T{ sourceRoot().flatMap(p => ls.rec(p.path)).map(PathRef(_)) }
        def classFiles = T{ compileAll(allSources()) }
        def jar = T{ Jvm.createJar(Loose.Agg(classFiles().path) ++ resourceRoot().map(_.path)) }

        def run(mainClsName: String) = T.command{
          %%('java, "-cp", classFiles().path, mainClsName)
        }
      }

      import Build._

      var evaluator = new TestEvaluator(Build)
      def eval[T](t: Task[T]) = {
        evaluator.apply(t)
      }
      def check(targets: Agg[Task[_]], expected: Agg[Task[_]]) = {
        evaluator.check(targets, expected)
      }

      def append(path: Path, txt: String) = ammonite.ops.write.append(path, txt)


      check(
        targets = Agg(jar),
        expected = Agg(allSources, classFiles, jar)
      )

      // Re-running with no changes results in nothing being evaluated
      check(targets = Agg(jar), expected = Agg())

      // Appending an empty string gets ignored due to file-content hashing
      append(sourceRootPath / "Foo.java", "")
      check(targets = Agg(jar), expected = Agg())

      // Appending whitespace forces a recompile, but the classfilesend up
      // exactly the same so no re-jarring.
      append(sourceRootPath / "Foo.java", " ")
      // Note that `sourceRoot` and `resourceRoot` never turn up in the `expected`
      // list, because they are `Source`s not `Target`s
      check(targets = Agg(jar), expected = Agg(/*sourceRoot, */allSources, classFiles))

      // Appending a new class changes the classfiles, which forces us to
      // re-create the final jar
      append(sourceRootPath / "Foo.java", "\nclass FooTwo{}")
      check(targets = Agg(jar), expected = Agg(allSources, classFiles, jar))

      // Tweaking the resources forces rebuild of the final jar, without
      // recompiling classfiles
      append(resourceRootPath / "hello.txt", " ")
      check(targets = Agg(jar), expected = Agg(jar))

      // You can swap evaluators halfway without any ill effects
      evaluator = new TestEvaluator(Build)

      // Asking for an intermediate target forces things to be build up to that
      // target only; these are re-used for any downstream targets requested
      append(sourceRootPath / "Bar.java", "\nclass BarTwo{}")
      append(resourceRootPath / "hello.txt", " ")
      check(targets = Agg(classFiles), expected = Agg(allSources, classFiles))
      check(targets = Agg(jar), expected = Agg(jar))
      check(targets = Agg(allSources), expected = Agg())

      append(sourceRootPath / "Bar.java", "\nclass BarThree{}")
      append(resourceRootPath / "hello.txt", " ")
      check(targets = Agg(resourceRoot), expected = Agg())
      check(targets = Agg(allSources), expected = Agg(allSources))
      check(targets = Agg(jar), expected = Agg(classFiles, jar))

      val jarContents = %%('jar, "-tf", evaluator.outPath/'jar/'dest/"out.jar")(evaluator.outPath).out.string
      val expectedJarContents =
        """META-INF/MANIFEST.MF
          |test/Bar.class
          |test/BarThree.class
          |test/BarTwo.class
          |test/Foo.class
          |test/FooTwo.class
          |hello.txt
          |""".stripMargin
      assert(jarContents == expectedJarContents)

      val executed = %%('java, "-cp", evaluator.outPath/'jar/'dest/"out.jar", "test.Foo")(evaluator.outPath).out.string
      assert(executed == (31337 + 271828) + "\n")

      for(i <- 0 until 3){
        // Build.run is not cached, so every time we eval it it has to
        // re-evaluate
        val Right((runOutput, evalCount)) = eval(Build.run("test.Foo"))
        assert(
          runOutput.out.string == (31337 + 271828) + "\n",
          evalCount == 1
        )
      }

      val Left(Result.Exception(ex, _)) = eval(Build.run("test.BarFour"))

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
      val Right((runOutput2, evalCount2)) = eval(Build.run("test.BarFour"))
      assert(
        runOutput2.out.string == "New Cls!\n",
        evalCount2 == 3
      )
      val Right((runOutput3, evalCount3)) = eval(Build.run("test.BarFour"))
      assert(
        runOutput3.out.string == "New Cls!\n",
        evalCount3 == 1
      )
    }
  }
}
