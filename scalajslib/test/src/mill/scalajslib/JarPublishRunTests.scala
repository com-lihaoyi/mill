package mill.scalajslib

import mill.eval.EvaluatorPaths
import mill.testkit.UnitTester
import utest._

import java.util.jar.JarFile
import scala.jdk.CollectionConverters.*

object JarPublishRunTests extends TestSuite {
  import CompileLinkTests.*

  def tests: Tests = Tests {
    test("jar") {
      test("containsSJSIRs") - UnitTester(HelloJSWorld, millSourcePath).scoped { eval =>
        val (scala, scalaJS) = HelloJSWorld.matrix.head
        val Right(result) =
          eval(HelloJSWorld.build(scala, scalaJS).jar)
        val jar = result.value.path
        val jarFile = new JarFile(jar.toIO)
        try {
          val entries = jarFile.entries().asScala.map(_.getName)
          assert(entries.contains("Main$.sjsir"))
        } finally jarFile.close()
      }
    }
    test("publish") {
      def testArtifactId(scalaVersion: String, scalaJSVersion: String, artifactId: String): Unit =
        UnitTester(HelloJSWorld, millSourcePath).scoped { eval =>
          val Right(result) = eval(HelloJSWorld.build(
            scalaVersion,
            scalaJSVersion
          ).artifactMetadata)
          assert(result.value.id == artifactId)
        }
      test("artifactId_10") {
        testArtifactId(
          HelloJSWorld.scalaVersions.head,
          "1.0.1",
          "hello-js-world_sjs1_2.13"
        )
      }
      test("artifactId_1") {
        testArtifactId(
          HelloJSWorld.scalaVersions.head,
          HelloJSWorld.scalaJSVersions.head,
          "hello-js-world_sjs1_2.13"
        )
      }
    }
    def checkRun(scalaVersion: String, scalaJSVersion: String): Unit =
      UnitTester(HelloJSWorld, millSourcePath).scoped { eval =>
        val task = HelloJSWorld.build(scalaVersion, scalaJSVersion).run()

        val Right(result) = eval(task)

        val paths = EvaluatorPaths.resolveDestPaths(eval.outPath, task)
        val log = os.read(paths.log)
        assert(
          result.evalCount > 0,
          log.contains("node")
          // TODO: re-enable somehow
          // In Scala.js 1.x, println's are sent to the stdout, not to the logger
          // log.contains("Scala.js")
        )
      }

    test("run") {
      testAllMatrix((scala, scalaJS) => checkRun(scala, scalaJS))
    }
  }

}
