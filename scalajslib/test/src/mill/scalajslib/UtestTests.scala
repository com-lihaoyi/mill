package mill.scalajslib

import mill._
import mill.api.Result
import mill.testkit.UnitTester
import mill.testrunner.TestResult
import utest._

object UtestTests extends TestSuite {
  import CompileLinkTests.*
  def runTests(testTask: define.NamedTask[(String, Seq[TestResult])])
      : Map[String, Map[String, TestResult]] =
    UnitTester(HelloJSWorld, millSourcePath).scoped { eval =>
      val Left(Result.Failure(_, Some(res))) = eval(testTask)

      val (doneMsg, testResults) = res
      testResults
        .groupBy(_.fullyQualifiedName)
        .view
        .mapValues(_.map(e => e.selector -> e).toMap)
        .toMap
    }

  def checkUtest(scalaVersion: String, scalaJSVersion: String, cached: Boolean) = {
    val resultMap = runTests(
      if (!cached) HelloJSWorld.build(scalaVersion, scalaJSVersion).`test-utest`.test()
      else HelloJSWorld.build(scalaVersion, scalaJSVersion).`test-utest`.testCached
    )

    val mainTests = resultMap("MainTests")
    val argParserTests = resultMap("ArgsParserTests")

    assert(
      mainTests.size == 2,
      mainTests("MainTests.vmName.containJs").status == "Success",
      mainTests("MainTests.vmName.containScala").status == "Success",
      argParserTests.size == 2,
      argParserTests("ArgsParserTests.one").status == "Success",
      argParserTests("ArgsParserTests.two").status == "Failure"
    )
  }

  def checkScalaTest(scalaVersion: String, scalaJSVersion: String, cached: Boolean) = {
    val resultMap = runTests(
      if (!cached) HelloJSWorld.build(scalaVersion, scalaJSVersion).`test-scalatest`.test()
      else HelloJSWorld.build(scalaVersion, scalaJSVersion).`test-scalatest`.testCached
    )

    val mainSpec = resultMap("MainSpec")
    val argParserSpec = resultMap("ArgsParserSpec")

    assert(
      mainSpec.size == 2,
      mainSpec("vmName should contain js").status == "Success",
      mainSpec("vmName should contain Scala").status == "Success",
      argParserSpec.size == 2,
      argParserSpec("parse should one").status == "Success",
      argParserSpec("parse should two").status == "Failure"
    )
  }
  def tests: Tests = Tests {

    test("utest") {
      testAllMatrix((scala, scalaJS) => checkUtest(scala, scalaJS, cached = false))
    }

    test("utestCached") {
      testAllMatrix((scala, scalaJS) => checkUtest(scala, scalaJS, cached = true))
    }

    def checkInheritedTargets[A](target: ScalaJSModule => T[A], expected: A) =
      UnitTester(HelloJSWorld, millSourcePath).scoped { eval =>
        val Right(mainResult) = eval(target(HelloJSWorld.inherited))
        val Right(testResult) = eval(target(HelloJSWorld.inherited.test))
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
