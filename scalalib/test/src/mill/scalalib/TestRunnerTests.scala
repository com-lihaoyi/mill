package mill.scalalib

import mill.{Agg, T}

import mill.api.Result
import mill.util.{TestEvaluator, TestUtil}
import utest._
import utest.framework.TestPath

import java.io.{ByteArrayOutputStream, PrintStream}

object TestRunnerTests extends TestSuite {
  object testrunner extends TestUtil.BaseModule with ScalaModule {
    override def millSourcePath = TestUtil.getSrcPathBase() / millOuterCtx.enclosing.split('.')

    def scalaVersion = sys.props.getOrElse("TEST_SCALA_2_13_VERSION", ???)

    object utest extends ScalaTests with TestModule.Utest {
      override def ivyDeps = T {
        super.ivyDeps() ++ Agg(
          ivy"com.lihaoyi::utest:${sys.props.getOrElse("TEST_UTEST_VERSION", ???)}"
        )
      }
    }

    object scalatest extends ScalaTests with TestModule.ScalaTest {
      override def ivyDeps = T {
        super.ivyDeps() ++ Agg(
          ivy"org.scalatest::scalatest:${sys.props.getOrElse("TEST_SCALATEST_VERSION", ???)}"
        )
      }
    }

    trait DoneMessage extends ScalaTests {
      override def ivyDeps = T {
        super.ivyDeps() ++ Agg(
          ivy"org.scala-sbt:test-interface:${sys.props.getOrElse("TEST_TEST_INTERFACE_VERSION", ???)}"
        )
      }
    }
    object doneMessageSuccess extends DoneMessage {
      def testFramework = "mill.scalalib.DoneMessageSuccessFramework"
    }
    object doneMessageFailure extends DoneMessage {
      def testFramework = "mill.scalalib.DoneMessageFailureFramework"
    }

    object ziotest extends ScalaTests with TestModule.ZioTest {
      override def ivyDeps = T {
        super.ivyDeps() ++ Agg(
          ivy"dev.zio::zio-test:${sys.props.getOrElse("TEST_ZIOTEST_VERSION", ???)}",
          ivy"dev.zio::zio-test-sbt:${sys.props.getOrElse("TEST_ZIOTEST_VERSION", ???)}"
        )
      }
    }
  }

  val resourcePath = os.pwd / "scalalib" / "test" / "resources" / "testrunner"

  def workspaceTest[T](
      m: TestUtil.BaseModule,
      outStream: PrintStream = System.out,
      resourcePath: os.Path = resourcePath
  )(t: TestEvaluator => T)(
      implicit tp: TestPath
  ): T = {
    val eval = new TestEvaluator(m, outStream = outStream)
    os.remove.all(m.millSourcePath)
    os.remove.all(eval.outPath)
    os.makeDir.all(m.millSourcePath / os.up)
    os.copy(resourcePath, m.millSourcePath)
    t(eval)
  }

  override def tests: Tests = Tests {
    "TestRunner" - {
      "utest" - {
        "test case lookup" - workspaceTest(testrunner) { eval =>
          val Right((result, _)) = eval.apply(testrunner.utest.test())
          val test = result.asInstanceOf[(String, Seq[mill.testrunner.TestResult])]
          assert(
            test._2.size == 3
          )
        }
        "testOnly" - {
          def testOnly(eval: TestEvaluator, args: Seq[String], size: Int) = {
            val Right((result1, _)) = eval.apply(testrunner.utest.testOnly(args: _*))
            val testOnly = result1.asInstanceOf[(String, Seq[mill.testrunner.TestResult])]
            assert(
              testOnly._2.size == size
            )
          }

          "suffix" - workspaceTest(testrunner) { eval =>
            testOnly(eval, Seq("*arTests"), 2)
          }
          "prefix" - workspaceTest(testrunner) { eval =>
            testOnly(eval, Seq("mill.scalalib.FooT*"), 1)
          }
          "exactly" - workspaceTest(testrunner) { eval =>
            testOnly(eval, Seq("mill.scalalib.FooTests"), 1)
          }
          "multi" - workspaceTest(testrunner) { eval =>
            testOnly(eval, Seq("*Bar*", "*bar*"), 2)
          }
        }
      }

      "doneMessage" - {
        test("failure") {
          val outStream = new ByteArrayOutputStream()
          workspaceTest(testrunner, outStream = new PrintStream(outStream, true)) { eval =>
            val Left(Result.Failure(msg, _)) = eval(testrunner.doneMessageFailure.test())
            val stdout = new String(outStream.toByteArray)
            assert(stdout.contains("test failure done message"))
          }
        }
        test("success") {
          val outStream = new ByteArrayOutputStream()
          workspaceTest(testrunner, outStream = new PrintStream(outStream, true)) { eval =>
            val Right(_) = eval(testrunner.doneMessageSuccess.test())
            val stdout = new String(outStream.toByteArray)
            assert(stdout.contains("test success done message"))
          }
        }
      }
      "ScalaTest" - {
        test("scalatest.test") {
          workspaceTest(testrunner) { eval =>
            val Right((testRes, count)) = eval(testrunner.scalatest.test())
            assert(testRes._2.size == 2)
          }
        }
      }

      "ZioTest" - {
        test("ziotest.test") {
          workspaceTest(testrunner) { eval =>
            val Right((testRes, count)) = eval(testrunner.ziotest.test())
            assert(testRes._2.size == 1)
          }
        }
      }
    }
  }
}
