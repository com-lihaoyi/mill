package mill.eval

import ammonite.ops.ImplicitWd._
import ammonite.ops._
import mill.define.{Target, Task}
import mill.discover.Discovered
import mill.modules.Jvm.jarUp
import mill.{T, Module}
import mill.util.OSet
import utest._
import JavaCompileJarTests.compileAll

object JavaMultipleSourceDirTests extends TestSuite{

  val tests = Tests{
    'javac {
      val workspacePath = pwd / 'target / 'workspace / 'javacTwo
      val javacSrcPath = pwd / 'core / 'src / 'test / 'examples / 'javac
      val javacDestPath = workspacePath / 'src

      mkdir(pwd / 'target / 'workspace / 'javacTwo)
      cp(javacSrcPath, javacDestPath)

      object Build extends Module{
        def sourceRootPath = javacDestPath / 'src
        def sourceRootPath2 = javacDestPath / 'src2

        def resourceRootPath = javacDestPath / 'resources

        def sourceRoot = T.sources(sourceRootPath, sourceRootPath2)
        def resourceRoot = T.sources{ resourceRootPath }
        def allSources = T{ sourceRoot().map(src => ls.rec(src.path).map(PathRef(_))).flatten }
        def classFiles = T{ compileAll(T.ctx().dest, allSources()) }
        def jar =  T{ jarUp(resourceRoot.unwrapped :+ classFiles) }

        def run(mainClsName: String) = T.command{
          %%('java, "-cp", classFiles().path, mainClsName)
        }
      }

      import Build._
      val mapping = Discovered.mapping(Build)

      val jcu = new JavaCompileUtils(workspacePath, mapping)
      import jcu._

      check(
        targets = OSet(jar),
        expected = OSet(allSources, classFiles, jar)
      )

      val jarContents = %%('jar, "-tf", workspacePath/'jar)(workspacePath).out.string
      val expectedJarContents =
        """META-INF/MANIFEST.MF
          |hello.txt
          |test/Bar.class
          |test/Baz.class
          |test/Foo.class
          |""".stripMargin
      assert(jarContents == expectedJarContents)

      val executed = %%('java, "-cp", workspacePath/'jar, "test.Baz")(workspacePath).out.string
      assert(executed == (73313 + 31337 + 271828) + "\n")

      for(i <- 0 until 3){
        // Build.run is not cached, so every time we eval it it has to
        // re-evaluate
        val Right((runOutput, evalCount)) = eval(Build.run("test.Baz"))
        assert(
          runOutput.out.string == (73313 + 31337 + 271828) + "\n",
          evalCount == 1
        )
      }
    }
  }
}
