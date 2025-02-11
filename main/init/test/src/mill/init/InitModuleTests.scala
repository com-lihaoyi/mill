package mill.init

import mill.api.Val
import mill.define.Discover
import mill.testkit.UnitTester
import mill.testkit.TestBaseModule
import utest._

import java.io.{ByteArrayOutputStream, PrintStream}

object InitModuleTests extends TestSuite {

  override def tests: Tests = Tests {

    test("init") {
      val outStream = new ByteArrayOutputStream()
      val errStream = new ByteArrayOutputStream()
      object initmodule extends TestBaseModule with InitModule {
        lazy val millDiscover = Discover[this.type]
      }
      val evaluator = UnitTester(
        initmodule,
        null,
        outStream = new PrintStream(outStream, true),
        errStream = new PrintStream(errStream, true)
      )
      test("no args") {
        val results = evaluator.evaluator.executeTasks(Seq(initmodule.init(None)))

        assert(results.failing.size == 0)

        val mill.api.ExecResult.Success(Val(value)) = results.rawValues.head: @unchecked
        val consoleShown = outStream.toString

        val examplesList: Seq[String] = value.asInstanceOf[Seq[String]]
        assert(
          consoleShown.startsWith(initmodule.msg),
          examplesList.forall(_.nonEmpty)
        )
      }
      test("non existing example") {
        val nonExistingModuleId = "nonExistingExampleId"
        val results = evaluator.evaluator.executeTasks(Seq(initmodule.init(Some(nonExistingModuleId))))
        assert(results.failing.size == 1)
        assert(errStream.toString.contains(initmodule.moduleNotExistMsg(nonExistingModuleId)))
      }
    }
  }
}
