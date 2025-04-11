package mill.init

import mill.api.Val
import mill.define.Discover
import mill.testkit.UnitTester
import mill.testkit.TestBaseModule
import utest._

import java.io.{ByteArrayOutputStream, OutputStream, PrintStream}

object InitModuleTests extends TestSuite {

  object initmodule extends TestBaseModule with InitModule {
    lazy val millDiscover = Discover[this.type]
  }

  override def tests: Tests = Tests {

    test("init") {
      test("no args") {
        val outStream = new ByteArrayOutputStream()
        val evaluator = UnitTester(
          initmodule,
          null,
          outStream = new PrintStream(outStream, true),
          errStream = new PrintStream(OutputStream.nullOutputStream(), true)
        )

        val results = evaluator.evaluator.execute(Seq(initmodule.init(None))).executionResults

        assert(results.transitiveFailing.size == 0)

        val mill.api.ExecResult.Success(Val(value)) = results.results.head: @unchecked
        val consoleShown = outStream.toString

        val examplesList: Seq[String] = value.asInstanceOf[Seq[String]]
        assert(
          consoleShown.startsWith(initmodule.msg),
          examplesList.forall(_.nonEmpty)
        )
      }
      test("non existing example") {
        val errStream = new ByteArrayOutputStream()
        val evaluator = UnitTester(
          initmodule,
          null,
          outStream = new PrintStream(OutputStream.nullOutputStream(), true),
          errStream = new PrintStream(errStream, true)
        )

        val nonExistingModuleId = "nonExistingExampleId"
        val results = evaluator.evaluator.execute(Seq(
          initmodule.init(Some(nonExistingModuleId))
        )).executionResults
        assert(results.transitiveFailing.size == 1)
        val err = errStream.toString
        assert(err.contains(initmodule.moduleNotExistMsg(nonExistingModuleId)))
      }
    }
  }
}
