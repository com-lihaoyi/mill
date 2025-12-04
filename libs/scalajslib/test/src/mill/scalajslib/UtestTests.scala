package mill.scalajslib

import mill._
import mill.api.ExecResult
import mill.javalib.testrunner.TestResult
import mill.testkit.UnitTester
import utest._

object UtestTests extends TestSuite {
  import CompileLinkTests._
  def runTests(testTask: api.Task.Named[(msg: String, results: Seq[TestResult])])
      : Unit =
    UnitTester(HelloJSWorld, millSourcePath).scoped { eval =>
      val Left(_: ExecResult.Failure[_]) = eval(testTask): @unchecked

//      val (doneMsg, testResults) = res
//      testResults
//        .groupBy(_.fullyQualifiedName)
//        .view
//        .mapValues(_.map(e => e.selector -> e).toMap)
//        .toMap
    }

  def checkUtest(scalaVersion: String, scalaJSVersion: String, cached: Boolean) = {
    runTests(
      if (!cached) HelloJSWorld.build(scalaVersion, scalaJSVersion).`test-utest`.testForked()
      else HelloJSWorld.build(scalaVersion, scalaJSVersion).`test-utest`.testCached
    )

//    val mainTests = resultMap("MainTests")
//    val argParserTests = resultMap("ArgsParserTests")
//
//    assert(
//      mainTests.size == 2,
//      mainTests("MainTests.vmName.containJs").status == "Success",
//      mainTests("MainTests.vmName.containScala").status == "Success",
//      argParserTests.size == 2,
//      argParserTests("ArgsParserTests.one").status == "Success",
//      argParserTests("ArgsParserTests.two").status == "Failure"
//    )
  }

  def checkScalaTest(scalaVersion: String, scalaJSVersion: String, cached: Boolean) = {
    runTests(
      if (!cached) HelloJSWorld.build(scalaVersion, scalaJSVersion).`test-scalatest`.testForked()
      else HelloJSWorld.build(scalaVersion, scalaJSVersion).`test-scalatest`.testCached
    )

//    val mainSpec = resultMap("MainSpec")
//    val argParserSpec = resultMap("ArgsParserSpec")
//
//    assert(
//      mainSpec.size == 2,
//      mainSpec("vmName should contain js").status == "Success",
//      mainSpec("vmName should contain Scala").status == "Success",
//      argParserSpec.size == 2,
//      argParserSpec("parse should one").status == "Success",
//      argParserSpec("parse should two").status == "Failure"
//    )
  }
  def tests: Tests = Tests {

    test("utest") {
      testAllMatrix((scala, scalaJS) => checkUtest(scala, scalaJS, cached = false))
    }

    test("utestCached") {
      testAllMatrix((scala, scalaJS) => checkUtest(scala, scalaJS, cached = true))
    }

    def checkInheritedTasks[A](task: ScalaJSModule => T[A], expected: A) =
      UnitTester(HelloJSWorld, millSourcePath).scoped { eval =>
        val Right(mainResult) = eval(task(HelloJSWorld.inherited)): @unchecked
        val Right(testResult) = eval(task(HelloJSWorld.inherited.test)): @unchecked
        assert(mainResult.value == expected)
        assert(testResult.value == expected)
      }
    test("test-scalacOptions") {
      checkInheritedTasks(_.scalacOptions, Seq("-deprecation"))
    }
    test("test-scalaOrganization") {
      checkInheritedTasks(_.scalaOrganization, "org.example")
    }
  }
}
