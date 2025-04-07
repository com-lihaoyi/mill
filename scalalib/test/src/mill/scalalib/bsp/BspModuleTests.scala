package mill.scalalib.bsp

import mill.define.{Cross, Discover}
import mill.define.ExecutionPaths
import mill.T
import mill.scalalib.{DepSyntax, JavaModule, ScalaModule}
import mill.testkit.UnitTester
import mill.testkit.TestBaseModule
import os.FilePath
import utest.*
import mill.util.TokenReaders._

object BspModuleTests extends TestSuite {

  val testScalaVersion = sys.props.getOrElse("TEST_SCALA_2_13_VERSION", ???)

  object MultiBase extends TestBaseModule {
    object HelloBsp extends ScalaModule {
      def scalaVersion = testScalaVersion
      override def ivyDeps = Seq(ivy"org.slf4j:slf4j-api:1.7.34")
    }
    object HelloBsp2 extends ScalaModule {
      def scalaVersion = testScalaVersion
      override def moduleDeps = Seq(HelloBsp)
      override def ivyDeps = Seq(ivy"ch.qos.logback:logback-classic:1.1.10")
    }
    lazy val millDiscover = Discover[this.type]
  }

  object InterDeps extends TestBaseModule {
    val maxCrossCount = 15
    val configs = 1.to(maxCrossCount)
    object Mod extends Cross[ModCross](configs)
    trait ModCross extends ScalaModule with Cross.Module[Int] {
      override def scalaVersion: T[String] = testScalaVersion
      // each depends on all others with lower index
      override def moduleDeps: Seq[JavaModule] =
        configs
          .filter(c => c < crossValue)
          .map(i => Mod(i))
    }
    lazy val millDiscover = Discover[this.type]
  }

  override def tests: Tests = Tests {
    test("bspCompileClasspath") {
      test("single module") - UnitTester(MultiBase, null).scoped { eval =>
        val Right(result) = eval.apply(
          MultiBase.HelloBsp.bspCompileClasspath
        ): @unchecked

        val relResult =
          result.value(eval.evaluator).map(s => os.Path(new java.net.URI(s)).last).toSeq.sorted
        val expected = Seq(
          "compile-resources",
          "slf4j-api-1.7.34.jar",
          s"scala-library-${testScalaVersion}.jar"
        ).sorted

        assert(
          relResult == expected,
          result.evalCount > 0
        )
      }
      test("dependent module") - UnitTester(MultiBase, null).scoped { eval =>
        val Right(result) = eval.apply(
          MultiBase.HelloBsp2.bspCompileClasspath
        ): @unchecked

        val relResults: Seq[FilePath] = result.value(eval.evaluator).iterator.map { p =>
          val path = os.Path(new java.net.URI(p))
          val name = path.last
          if (name.endsWith(".jar")) os.rel / name
          else path
        }.toSeq.sortBy(_.toString)

        val expected: Seq[FilePath] = Seq(
          MultiBase.HelloBsp.moduleDir / "compile-resources",
          MultiBase.HelloBsp2.moduleDir / "compile-resources",
          ExecutionPaths.resolve(eval.outPath, MultiBase.HelloBsp.compile)
            .dest / "classes",
          os.rel / "slf4j-api-1.7.34.jar",
          os.rel / "logback-core-1.1.10.jar",
          os.rel / "logback-classic-1.1.10.jar",
          os.rel / s"scala-library-${testScalaVersion}.jar"
        ).sortBy(_.toString)

        assert(
          relResults == expected,
          result.evalCount > 0
        )
      }
      test("interdependencies are fast") {
        test("reference (no BSP)") {
          def runNoBsp(entry: Int, maxTime: Int) = UnitTester(InterDeps, null).scoped { eval =>
            val start = System.currentTimeMillis()
            val Right(_) = eval.apply(
              InterDeps.Mod(entry).compileClasspath
            ): @unchecked
            val timeSpent = System.currentTimeMillis() - start
            assert(timeSpent < maxTime)
            s"${timeSpent} msec"
          }
          test("index 1 (no deps)") { runNoBsp(1, 5000) }
          test("index 10") { runNoBsp(10, 30000) }
          test("index 15") { runNoBsp(15, 30000) }
        }
        def run(entry: Int, maxTime: Int) = retry(3) {
          UnitTester(InterDeps, null).scoped { eval =>
            val start = System.currentTimeMillis()
            val Right(_) = eval.apply(
              InterDeps.Mod(entry).bspCompileClasspath
            ): @unchecked
            val timeSpent = System.currentTimeMillis() - start
            assert(timeSpent < maxTime)
            s"${timeSpent} msec"
          }
        }
        test("index 1 (no deps)") { run(1, 500) }
        test("index 10") { run(10, 5000) }
        test("index 15") { run(15, 15000) }
      }
    }
  }
}
