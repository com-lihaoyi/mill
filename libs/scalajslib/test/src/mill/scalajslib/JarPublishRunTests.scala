package mill.scalajslib

import mill._
import mill.api.ExecutionPaths
import mill.testkit.UnitTester
import utest._

import java.util.jar.JarFile
import scala.jdk.CollectionConverters._

object JarPublishRunTests extends TestSuite {
  import CompileLinkTests._

  def tests: Tests = Tests {
    test("jar") {
      test("containsSJSIRs") - UnitTester(HelloJSWorld, millSourcePath).scoped { eval =>
        val (scala, scalaJS) = HelloJSWorld.matrix.head
        val Right(result) =
          eval(HelloJSWorld.build(scala, scalaJS).jar): @unchecked
        val jar = result.value.path
        val jarFile = JarFile(jar.toIO)
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
          ).artifactMetadata): @unchecked
          assert(result.value.id == artifactId)
        }
      test("artifactId_10") {
        testArtifactId(
          HelloJSWorld.matrix.head._1,
          "1.19.0",
          "hello-js-world_sjs1_2.13"
        )
      }
      test("artifactId_1") {
        testArtifactId(
          HelloJSWorld.matrix.head._1,
          HelloJSWorld.matrix.head._2,
          "hello-js-world_sjs1_2.13"
        )
      }
    }
    def checkRun(scalaVersion: String, scalaJSVersion: String): Unit =
      UnitTester(HelloJSWorld, millSourcePath).scoped { eval =>
        val task = HelloJSWorld.build(scalaVersion, scalaJSVersion).run()

        val Right(result) = eval(task): @unchecked

        val paths = ExecutionPaths.resolve(eval.outPath, task)
        val log = os.read(paths.log)
        assert(
          result.evalCount > 0,
          log.contains("node"),
          log.contains("Scala.js")
        )
      }

    test("run") {
      testAllMatrix((scala, scalaJS) => checkRun(scala, scalaJS))
    }
  }

}
