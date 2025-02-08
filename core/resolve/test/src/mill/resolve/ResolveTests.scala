package mill.resolve

import mill.define.{Discover, NamedTask, TaskModule, ModuleRef}
import mill.util.TestGraphs
import mill.util.TestGraphs._
import mill.testkit.TestBaseModule
import mill.{Task, Module, Cross}
import utest._
object ResolveTests extends TestSuite {

  object doubleNestedModule extends TestBaseModule {
    def single = Task { 5 }
    object nested extends Module {
      def single = Task { 7 }

      object inner extends Module {
        def single = Task { 9 }
      }
    }
    lazy val millDiscover = Discover[this.type]
  }

  def isShortError(x: Either[String, _], s: String) =
    x.left.exists(_.contains(s)) &&
      // Make sure the stack traces are truncated and short-ish, and do not
      // contain the entire Mill internal call stack at point of failure
      x.left.exists(_.linesIterator.size < 25)

  val tests = Tests {
    val graphs = new mill.util.TestGraphs()
    import graphs.*
    test("single") {
      val check = new Checker(singleton)
      test("pos") - check("single", Right(Set(_.single)), Set("single"))
      test("wildcard") - check("_", Right(Set(_.single)), Set("single"))
      test("neg1") - check("sngle", Left("Cannot resolve sngle. Did you mean single?"))
      test("neg2") - check("snigle", Left("Cannot resolve snigle. Did you mean single?"))
      test("neg3") - check("nsiigle", Left("Cannot resolve nsiigle. Did you mean single?"))
      test("neg4") - check(
        "ansiigle",
        Left("Cannot resolve ansiigle. Try `mill resolve _` to see what's available.")
      )
      test("neg5") - check(
        "doesntExist",
        Left("Cannot resolve doesntExist. Try `mill resolve _` to see what's available.")
      )
      test("neg6") - check(
        "single.doesntExist",
        Left("Cannot resolve single.doesntExist. single resolves to a Task with no children.")
      )
      test("neg7") - check(
        "",
        Left("Target selector must not be empty. Try `mill resolve _` to see what's available.")
      )
    }
    test("backtickIdentifiers") {
      val check = new Checker(bactickIdentifiers)
      test("pos1") - check("up-target", Right(Set(_.`up-target`)), Set("up-target"))
      test("pos2") - check("a-down-target", Right(Set(_.`a-down-target`)), Set("a-down-target"))
      test("neg1") - check("uptarget", Left("Cannot resolve uptarget. Did you mean up-target?"))
      test("neg2") - check("upt-arget", Left("Cannot resolve upt-arget. Did you mean up-target?"))
      test("neg3") - check(
        "up-target.doesntExist",
        Left("Cannot resolve up-target.doesntExist. up-target resolves to a Task with no children.")
      )
      test("neg4") - check(
        "",
        Left("Target selector must not be empty. Try `mill resolve _` to see what's available.")
      )
      test("neg5") - check(
        "invisible",
        Left("Cannot resolve invisible. Try `mill resolve _` to see what's available.")
      )
      test("negBadParse") - check(
        "invisible&",
        Left("Parsing exception Position 1:10, found \"&\"")
      )
      test("nested") {
        test("pos") - check(
          "nested-module.nested-target",
          Right(Set(_.`nested-module`.`nested-target`)),
          Set("nested-module.nested-target")
        )
        test("neg") - check(
          "nested-module.doesntExist",
          Left(
            "Cannot resolve nested-module.doesntExist. Try `mill resolve nested-module._` to see what's available."
          )
        )
      }
    }
    test("nested") {
      val check = new Checker(nestedModule)
      test("pos1") - check("single", Right(Set(_.single)), Set("single"))
      test("pos2") - check("nested.single", Right(Set(_.nested.single)), Set("nested.single"))
      test("pos3") - check(
        "classInstance.single",
        Right(Set(_.classInstance.single)),
        Set("classInstance.single")
      )
      test("posCurly1") - check(
        "{nested,classInstance}.single",
        Right(Set(_.nested.single, _.classInstance.single)),
        Set("nested.single", "classInstance.single")
      )
      test("posCurly2") - check(
        "{single,{nested,classInstance}.single}",
        Right(Set(_.single, _.nested.single, _.classInstance.single)),
        Set("single", "nested.single", "classInstance.single")
      )
      test("posCurly3") - check(
        "{single,nested.single,classInstance.single}",
        Right(Set(_.single, _.nested.single, _.classInstance.single)),
        Set("single", "nested.single", "classInstance.single")
      )
      test("posCurly4") - check(
        "{,nested.,classInstance.}single",
        Right(Set(_.single, _.nested.single, _.classInstance.single)),
        Set("single", "nested.single", "classInstance.single")
      )
      test("neg1") - check(
        "doesntExist",
        Left("Cannot resolve doesntExist. Try `mill resolve _` to see what's available.")
      )
      test("neg2") - check(
        "single.doesntExist",
        Left("Cannot resolve single.doesntExist. single resolves to a Task with no children.")
      )
      test("neg3") - check(
        "nested.doesntExist",
        Left(
          "Cannot resolve nested.doesntExist. Try `mill resolve nested._` to see what's available."
        )
      )
      test("neg3") - check(
        "nested.singel",
        Left("Cannot resolve nested.singel. Did you mean nested.single?")
      )
      test("neg4") - check(
        "classInstance.doesntExist",
        Left(
          "Cannot resolve classInstance.doesntExist. Try `mill resolve classInstance._` to see what's available."
        )
      )
      test("wildcard") - check(
        "_.single",
        Right(Set(
          _.classInstance.single,
          _.nested.single
        )),
        Set("nested.single", "classInstance.single")
      )
      test("wildcardNeg") - check(
        "_._.single",
        Left(
          "Cannot resolve _._.single. Try `mill resolve _` or `mill resolve __.single` to see what's available."
        )
      )
      test("wildcardNeg2") - check(
        "_._.__",
        Left("Cannot resolve _._.__. Try `mill resolve _` to see what's available.")
      )
      test("wildcardNeg3") - check(
        "nested._.foobar",
        Left("Cannot resolve nested._.foobar. nested._ resolves to a Task with no children.")
      )
      test("wildcard2") - check(
        "__.single",
        Right(Set(
          _.single,
          _.classInstance.single,
          _.nested.single
        )),
        Set("single", "nested.single", "classInstance.single")
      )

      test("wildcard3") - check(
        "_.__.single",
        Right(Set(
          _.classInstance.single,
          _.nested.single
        )),
        Set("nested.single", "classInstance.single")
      )
    }
    test("doubleNested") {
      val check = new Checker(doubleNestedModule)
      test("pos1") - check("single", Right(Set(_.single)), Set("single"))
      test("pos2") - check("nested.single", Right(Set(_.nested.single)), Set("nested.single"))
      test("pos3") - check(
        "nested.inner.single",
        Right(Set(_.nested.inner.single)),
        Set("nested.inner.single")
      )
      test("neg1") - check(
        "doesntExist",
        Left("Cannot resolve doesntExist. Try `mill resolve _` to see what's available.")
      )
      test("neg2") - check(
        "nested.doesntExist",
        Left(
          "Cannot resolve nested.doesntExist. Try `mill resolve nested._` to see what's available."
        )
      )
      test("neg3") - check(
        "nested.inner.doesntExist",
        Left(
          "Cannot resolve nested.inner.doesntExist. Try `mill resolve nested.inner._` to see what's available."
        )
      )
      test("neg4") - check(
        "nested.inner.doesntExist.alsoDoesntExist2",
        Left(
          "Cannot resolve nested.inner.doesntExist.alsoDoesntExist2. Try `mill resolve nested.inner._` to see what's available."
        )
      )
    }

  }
}
