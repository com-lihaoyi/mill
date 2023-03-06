package mill
package contrib.jmh

import mill.api.PathRef
import mill.eval.EvaluatorPaths
import mill.scalalib.ScalaModule
import mill.util.{TestEvaluator, TestUtil}
import os.Path
import utest._
import utest.framework.TestPath

object JmhModuleTest extends TestSuite {

  object jmh extends TestUtil.BaseModule with ScalaModule with JmhModule {

    override def scalaVersion = sys.props.getOrElse("TEST_SCALA_2_13_VERSION", ???)
    override def jmhCoreVersion = "1.35"
    override def millSourcePath = TestUtil.getSrcPathBase() / millOuterCtx.enclosing.split('.')
  }

  val testModuleSourcesPath: Path =
    os.pwd / "contrib" / "jmh" / "test" / "resources" / "jmh"

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
    test("jmh") {
      "listJmhBenchmarks" - workspaceTest(jmh) { eval =>
        val paths = EvaluatorPaths.resolveDestPaths(eval.outPath, jmh.listJmhBenchmarks())
        val outFile = paths.dest / "benchmarks.out"
        val Right((result, _)) = eval(jmh.listJmhBenchmarks("-o", outFile.toString))
        val expected = """Benchmarks:
                         |mill.contrib.jmh.Bench2.log
                         |mill.contrib.jmh.Bench2.sqrt
                         |mill.contrib.jmh.Bench1.measureShared
                         |mill.contrib.jmh.Bench1.measureUnshared""".stripMargin
        val out = os.read.lines(outFile).map(_.trim).mkString(System.lineSeparator())
        assert(out == expected)
      }
    }
  }
}
