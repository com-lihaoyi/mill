package mill.init

import mill.api.{PathRef, Result, Val}
import mill.{Agg, T}
import mill.define.{Cross, Discover, Module, Task}
import mill.testkit.UnitTester
import mill.testkit.TestBaseModule
import mill.testkit.UnitTester
import mill.testkit.TestBaseModule
import utest._

import java.io.{ByteArrayOutputStream, PrintStream}
import scala.util.Using

object InitModuleTests extends TestSuite {

  override def tests: Tests = Tests {

    test("init") {
      val outStream = new ByteArrayOutputStream()
      val errStream = new ByteArrayOutputStream()
      object initmodule extends TestBaseModule with InitModule
      val evaluator = UnitTester(
        initmodule,
        null,
        outStream = new PrintStream(outStream, true),
        errStream = new PrintStream(errStream, true)
      )
      test("no args") {
        val results = evaluator.evaluator.evaluate(Agg(initmodule.init(None)))

        assert(results.failing.keyCount == 0)

        val Result.Success(Val(value)) = results.rawValues.head
        val consoleShown = outStream.toString

        val examplesList: Seq[String] = value.asInstanceOf[Seq[String]]
        assert(
          consoleShown.startsWith(initmodule.msg),
          examplesList.forall(_.nonEmpty)
        )
      }
      test("non existing example") {
        val nonExistingModuleId = "nonExistingExampleId"
        val results = evaluator.evaluator.evaluate(Agg(initmodule.init(Some(nonExistingModuleId))))
        assert(results.failing.keyCount == 1)
        assert(errStream.toString.contains(initmodule.moduleNotExistMsg(nonExistingModuleId)))
      }
    }
  }
}
