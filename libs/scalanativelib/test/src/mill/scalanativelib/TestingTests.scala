package mill.scalanativelib

import mill.*
import mill.api.ExecResult
import mill.javalib.testrunner.TestResult
import mill.scalanativelib.api.*
import mill.testkit.UnitTester
import utest.*

object TestingTests extends TestSuite {
  import CompileRunTests._
  def tests: Tests = Tests {

    def runTests(testTask: Task.Named[(msg: String, results: Seq[TestResult])])
        : Unit =
      UnitTester(HelloNativeWorld, millSourcePath).scoped { eval =>
        val Left(_: ExecResult.Failure[_]) = eval(testTask): @unchecked

//        val (doneMsg, testResults) = res
//        testResults
//          .groupBy(_.fullyQualifiedName)
//          .view
//          .mapValues(_.map(e => e.selector -> e).toMap)
//          .toMap
      }

    def checkUtest(
        scalaVersion: String,
        scalaNativeVersion: String,
        mode: ReleaseMode,
        cached: Boolean
    ) = {
      runTests(
        if (!cached)
          HelloNativeWorld.build(scalaVersion, scalaNativeVersion, mode).test.testForked()
        else HelloNativeWorld.build(scalaVersion, scalaNativeVersion, mode).test.testCached
      )

//      val mainTests = resultMap("hellotest.MainTests")
//      val argParserTests = resultMap("hellotest.ArgsParserTests")
//
//      assert(
//        mainTests.size == 3,
//        mainTests("hellotest.MainTests.vmName.containNative").status == "Success",
//        mainTests("hellotest.MainTests.vmName.containScala").status == "Success",
//        argParserTests.size == 2,
//        argParserTests("hellotest.ArgsParserTests.one").status == "Success",
//        argParserTests("hellotest.ArgsParserTests.two").status == "Failure"
//      )
    }

    test("test") - {
      val cached = false

      testAllMatrix((scala, scalaNative, releaseMode) =>
        checkUtest(scala, scalaNative, releaseMode, cached)
      )
    }

    def checkInheritedTasks[A](task: ScalaNativeModule => T[A], expected: A) =
      UnitTester(HelloNativeWorld, millSourcePath).scoped { eval =>
        val Right(mainResult) = eval(task(HelloNativeWorld.inherited)): @unchecked
        val Right(testResult) = eval(task(HelloNativeWorld.inherited.test)): @unchecked
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
