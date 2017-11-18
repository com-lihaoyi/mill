package mill


import ammonite.ops._
import ImplicitWd._
import mill.define.{Target, Task}
import mill.discover.Discovered
import mill.eval.{Evaluator, Result}
import mill.modules.Jvm.jarUp
import mill.util.OSet
import utest._

object JavaCompileJarTests extends TestSuite{
  def compileAll(dest: Path, sources: Seq[PathRef]) = {
    mkdir(dest)
    import ammonite.ops._
    %("javac", sources.map(_.path.toString()), "-d", dest)(wd = dest)
    PathRef(dest)
  }

  val tests = Tests{
    'javac {
      val workspacePath = pwd / 'target / 'workspace / 'javac
      val javacSrcPath = pwd / 'core / 'src / 'test / 'examples / 'javac
      val javacDestPath = workspacePath / 'src

      mkdir(pwd / 'target / 'workspace / 'javac)
      cp(javacSrcPath, javacDestPath)

      object Build extends Module{
        def sourceRootPath = javacDestPath / 'src
        def resourceRootPath = javacDestPath / 'resources

        // sourceRoot -> allSources -> classFiles
        //                                |
        //                                v
        //           resourceRoot ---->  jar
        def sourceRoot = T.source{ sourceRootPath }
        def resourceRoot = T.source{ resourceRootPath }
        def allSources = T{ ls.rec(sourceRoot().path).map(PathRef(_)) }
        def classFiles = T{ compileAll(T.ctx().dest, allSources()) }
        def jar = T{ jarUp(resourceRoot, classFiles) }

        def run(mainClsName: String) = T.command{
          %%('java, "-cp", classFiles().path, mainClsName)
        }
      }

      import Build._
      val mapping = Discovered.mapping(Build)

      def eval[T](t: Task[T]): Either[Result.Failing, (T, Int)] = {
        val evaluator = new Evaluator(workspacePath, mapping)
        val evaluated = evaluator.evaluate(OSet(t))

        if (evaluated.failing.keyCount == 0){
          Right(Tuple2(
            evaluated.rawValues(0).asInstanceOf[Result.Success[T]].value,
            evaluated.evaluated.collect{
              case t: Target[_] if mapping.contains(t) => t
              case t: mill.define.Command[_] => t
            }.size
          ))
        }else{
          Left(evaluated.failing.lookupKey(evaluated.failing.keys().next).items.next())
        }

      }
      def check(targets: OSet[Task[_]], expected: OSet[Task[_]]) = {
        val evaluator = new Evaluator(workspacePath, mapping)

        val evaluated = evaluator.evaluate(targets)
          .evaluated
          .flatMap(_.asTarget)
          .filter(mapping.contains)
        assert(evaluated == expected)
      }

      def append(path: Path, txt: String) = ammonite.ops.write.append(path, txt)


      check(
        targets = OSet(jar),
        expected = OSet(allSources, classFiles, jar)
      )

      // Re-running with no changes results in nothing being evaluated
      check(targets = OSet(jar), expected = OSet())

      // Appending an empty string gets ignored due to file-content hashing
      append(sourceRootPath / "Foo.java", "")
      check(targets = OSet(jar), expected = OSet())

      // Appending whitespace forces a recompile, but the classfilesend up
      // exactly the same so no re-jarring.
      append(sourceRootPath / "Foo.java", " ")
      // Note that `sourceRoot` and `resourceRoot` never turn up in the `expected`
      // list, because they are `Source`s not `Target`s
      check(targets = OSet(jar), expected = OSet(/*sourceRoot, */allSources, classFiles))

      // Appending a new class changes the classfiles, which forces us to
      // re-create the final jar
      append(sourceRootPath / "Foo.java", "\nclass FooTwo{}")
      check(targets = OSet(jar), expected = OSet(allSources, classFiles, jar))

      // Tweaking the resources forces rebuild of the final jar, without
      // recompiling classfiles
      append(resourceRootPath / "hello.txt", " ")
      check(targets = OSet(jar), expected = OSet(jar))

      // Asking for an intermediate target forces things to be build up to that
      // target only; these are re-used for any downstream targets requested
      append(sourceRootPath / "Bar.java", "\nclass BarTwo{}")
      append(resourceRootPath / "hello.txt", " ")
      check(targets = OSet(classFiles), expected = OSet(allSources, classFiles))
      check(targets = OSet(jar), expected = OSet(jar))
      check(targets = OSet(allSources), expected = OSet())

      append(sourceRootPath / "Bar.java", "\nclass BarThree{}")
      append(resourceRootPath / "hello.txt", " ")
      check(targets = OSet(resourceRoot), expected = OSet())
      check(targets = OSet(allSources), expected = OSet(allSources))
      check(targets = OSet(jar), expected = OSet(classFiles, jar))

      val jarContents = %%('jar, "-tf", workspacePath/'jar)(workspacePath).out.string
      val expectedJarContents =
        """META-INF/MANIFEST.MF
          |hello.txt
          |test/Bar.class
          |test/BarThree.class
          |test/BarTwo.class
          |test/Foo.class
          |test/FooTwo.class
          |""".stripMargin
      assert(jarContents == expectedJarContents)

      val executed = %%('java, "-cp", workspacePath/'jar, "test.Foo")(workspacePath).out.string
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

      val Left(Result.Exception(ex)) = eval(Build.run("test.BarFour"))

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
