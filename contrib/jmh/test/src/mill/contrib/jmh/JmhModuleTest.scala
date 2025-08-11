package mill
package contrib.jmh

import mill.api.Discover
import mill.api.ExecutionPaths
import mill.scalalib.ScalaModule
import mill.testkit.UnitTester
import mill.testkit.TestRootModule
import os.Path
import utest.*

object JmhModuleTest extends TestSuite {

  object jmh extends TestRootModule with ScalaModule with JmhModule {

    override def scalaVersion = sys.props.getOrElse("TEST_SCALA_2_13_VERSION", ???)
    override def jmhCoreVersion = "1.35"

    lazy val millDiscover = Discover[this.type]
  }
  val testModuleSourcesPath: Path = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "jmh"

  def tests = Tests {
    test("jmh") {
      test("listJmhBenchmarks") - UnitTester(jmh, testModuleSourcesPath).scoped { eval =>
        val paths = ExecutionPaths.resolve(eval.outPath, jmh.listJmhBenchmarks())
        val outFile = paths.dest / "benchmarks.out"
        val Right(_) = eval(jmh.listJmhBenchmarks("-o", outFile.toString)): @unchecked
        val expected =
          """Benchmarks:
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
