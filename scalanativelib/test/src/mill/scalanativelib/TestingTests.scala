package mill.scalanativelib

import mill._
import mill.api.Result
import mill.define.Discover
import mill.eval.EvaluatorPaths
import mill.scalalib.api.ZincWorkerUtil
import mill.scalalib.publish.{Developer, License, PomSettings, VersionControl}
import mill.scalalib.{DepSyntax, PublishModule, ScalaModule, TestModule}
import mill.scalanativelib.api._
import mill.testkit.{TestBaseModule, UnitTester}
import mill.testrunner.TestResult
import mill.util.TestUtil
import utest._

import java.util.jar.JarFile
import scala.jdk.CollectionConverters._

object TestingTests extends TestSuite {
  import CompileRunTests._
  def tests: Tests = Tests {

    def runTests(testTask: define.NamedTask[(String, Seq[TestResult])])
        : Map[String, Map[String, TestResult]] =
      UnitTester(HelloNativeWorld, millSourcePath).scoped { eval =>
        val Left(Result.Failure(_, Some(res))) = eval(testTask)

        val (doneMsg, testResults) = res
        testResults
          .groupBy(_.fullyQualifiedName)
          .view
          .mapValues(_.map(e => e.selector -> e).toMap)
          .toMap
      }

    def checkUtest(
        scalaVersion: String,
        scalaNativeVersion: String,
        mode: ReleaseMode,
        cached: Boolean
    ) = {
      val resultMap = runTests(
        if (!cached) HelloNativeWorld.build(scalaVersion, scalaNativeVersion, mode).test.test()
        else HelloNativeWorld.build(scalaVersion, scalaNativeVersion, mode).test.testCached
      )

      val mainTests = resultMap("hellotest.MainTests")
      val argParserTests = resultMap("hellotest.ArgsParserTests")

      assert(
        mainTests.size == 3,
        mainTests("hellotest.MainTests.vmName.containNative").status == "Success",
        mainTests("hellotest.MainTests.vmName.containScala").status == "Success",
        argParserTests.size == 2,
        argParserTests("hellotest.ArgsParserTests.one").status == "Success",
        argParserTests("hellotest.ArgsParserTests.two").status == "Failure"
      )
    }

    test("test") - {
      val cached = false

      testAllMatrix((scala, scalaNative, releaseMode) =>
        checkUtest(scala, scalaNative, releaseMode, cached)
      )
    }

    def checkInheritedTargets[A](target: ScalaNativeModule => T[A], expected: A) =
      UnitTester(HelloNativeWorld, millSourcePath).scoped { eval =>
        val Right(mainResult) = eval(target(HelloNativeWorld.inherited))
        val Right(testResult) = eval(target(HelloNativeWorld.inherited.test))
        assert(mainResult.value == expected)
        assert(testResult.value == expected)
      }
    test("test-scalacOptions") {
      checkInheritedTargets(_.scalacOptions, Seq("-deprecation"))
    }
    test("test-scalaOrganization") {
      checkInheritedTargets(_.scalaOrganization, "org.example")
    }
  }

}
