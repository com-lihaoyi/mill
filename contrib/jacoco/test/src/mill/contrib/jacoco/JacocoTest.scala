package mill
package contrib.jacoco
import mill.scalalib._
import mill.eval.EvaluatorPaths
import mill.scalalib.ScalaModule
import mill.util.{TestEvaluator, TestUtil}
import os.Path
import utest._
import utest.framework.TestPath

object JacocoTest extends TestSuite {

  object main extends TestUtil.BaseModule with JavaModule {
    object test extends JavaModuleTests with JacocoTestModule with TestModule.Junit4 {
      override def ivyDeps: T[Agg[Dep]] = super.ivyDeps() ++ Agg(
        ivy"com.novocode:junit-interface:0.11",
        ivy"junit:junit:4.12"
      )
    }

    def verify(millVersion: String): Command[Unit] = T.command {
      val destDir =
        if (millVersion.startsWith("0.9")) "jacocoReportFull" :: "dest" :: Nil
        else "jacocoReportFull.dest" :: Nil
      val jacocoPath = os.pwd / "out" / "de" / "tobiasroeser" / "mill" / "jacoco" / "Jacoco" / destDir

      val xml = jacocoPath / "jacoco.xml"
      assert(os.exists(jacocoPath))
      assert(os.exists(xml))
      val contents = os.read(xml)
      assert(contents.contains("""<class name="test/Main" sourcefilename="Main.java">"""))
      assert(contents.contains("""<sourcefile name="Main.java">"""))
      ()
    }
  }

  val testModuleSourcesPath: Path =
    os.pwd / "contrib" / "jacoco" / "test" / "resources" / "jacoco"

  private def workspaceTest(m: TestUtil.BaseModule)(t: TestEvaluator => Unit)(
      implicit tp: TestPath
  ): Unit = {
    val eval = new TestEvaluator(m)
    os.remove.all(m.millSourcePath)
    os.remove.all(eval.outPath)
    os.makeDir.all(m.millSourcePath / os.up)
    os.copy(testModuleSourcesPath, m.millSourcePath)
    t(eval)
  }

  def tests = Tests {
    test("jacoco") - workspaceTest(main) { eval =>
      eval(mill.contrib.jacoco.Jacoco.jacocoReportFull(eval.evaluator))
    }
  }
}
