package mill.resolve

import mill.api.Result
import mill.define.Discover
import mill.util.TestGraphs
import mill.util.TestGraphs.*
import mill.testkit.TestBaseModule
import mill.{Module, Task}
import utest.*
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

  def isShortError(x: Result[?], s: String) =
    x.errorOpt.exists(_.contains(s)) &&
      // Make sure the stack traces are truncated and short-ish, and do not
      // contain the entire Mill internal call stack at point of failure
      x.errorOpt.exists(_.linesIterator.size < 25)

  val tests = Tests {
    test("single") {
      val check = new Checker(singleton)
      test("pos") - check("single", Result.Success(Set(_.single)), Set("single"))
      test("wildcard") - check("_", Result.Success(Set(_.single)), Set("single"))
      test("neg1") - check("sngle", Result.Failure("Cannot resolve sngle. Did you mean single?"))
      test("neg2") - check("snigle", Result.Failure("Cannot resolve snigle. Did you mean single?"))
      test("neg3") - check(
        "nsiigle",
        Result.Failure("Cannot resolve nsiigle. Did you mean single?")
      )
      test("neg4") - check(
        "ansiigle",
        Result.Failure("Cannot resolve ansiigle. Try `mill resolve _` to see what's available.")
      )
      test("neg5") - check(
        "doesntExist",
        Result.Failure("Cannot resolve doesntExist. Try `mill resolve _` to see what's available.")
      )
      test("neg6") - check(
        "single.doesntExist",
        Result.Failure(
          "Cannot resolve single.doesntExist. single resolves to a Task with no children."
        )
      )
      test("neg7") - check(
        "",
        Result.Failure(
          "Target selector must not be empty. Try `mill resolve _` to see what's available."
        )
      )
    }
    test("backtickIdentifiers") {
      val check = new Checker(bactickIdentifiers)
      test("pos1") - check("up-target", Result.Success(Set(_.`up-target`)), Set("up-target"))
      test("pos2") - check(
        "a-down-target",
        Result.Success(Set(_.`a-down-target`)),
        Set("a-down-target")
      )
      test("neg1") - check(
        "uptarget",
        Result.Failure("Cannot resolve uptarget. Did you mean up-target?")
      )
      test("neg2") - check(
        "upt-arget",
        Result.Failure("Cannot resolve upt-arget. Did you mean up-target?")
      )
      test("neg3") - check(
        "up-target.doesntExist",
        Result.Failure(
          "Cannot resolve up-target.doesntExist. up-target resolves to a Task with no children."
        )
      )
      test("neg4") - check(
        "",
        Result.Failure(
          "Target selector must not be empty. Try `mill resolve _` to see what's available."
        )
      )
      test("neg5") - check(
        "invisible",
        Result.Failure(
          "Cannot resolve invisible. Try `mill resolve _` or `mill resolve __.invisible&` to see what's available."
        )
      )
      test("negBadParse") - check(
        "invisible&",
        Result.Failure("Parsing exception Position 1:10, found \"&\"")
      )
      test("nested") {
        test("pos") - check(
          "nested-module.nested-target",
          Result.Success(Set(_.`nested-module`.`nested-target`)),
          Set("nested-module.nested-target")
        )
        test("neg") - check(
          "nested-module.doesntExist",
          Result.Failure(
            "Cannot resolve nested-module.doesntExist. Try `mill resolve nested-module._` to see what's available."
          )
        )
      }
    }
    test("nested") {
      val check = new Checker(nestedModule)
      test("pos1") - check("single", Result.Success(Set(_.single)), Set("single"))
      test("pos2") - check(
        "nested.single",
        Result.Success(Set(_.nested.single)),
        Set("nested.single")
      )
      test("pos3") - check(
        "classInstance.single",
        Result.Success(Set(_.classInstance.single)),
        Set("classInstance.single")
      )
      test("posCurly1") - check(
        "{nested,classInstance}.single",
        Result.Success(Set(_.nested.single, _.classInstance.single)),
        Set("nested.single", "classInstance.single")
      )
      test("posCurly2") - check(
        "{single,{nested,classInstance}.single}",
        Result.Success(Set(_.single, _.nested.single, _.classInstance.single)),
        Set("single", "nested.single", "classInstance.single")
      )
      test("posCurly3") - check(
        "{single,nested.single,classInstance.single}",
        Result.Success(Set(_.single, _.nested.single, _.classInstance.single)),
        Set("single", "nested.single", "classInstance.single")
      )
      test("posCurly4") - check(
        "{,nested.,classInstance.}single",
        Result.Success(Set(_.single, _.nested.single, _.classInstance.single)),
        Set("single", "nested.single", "classInstance.single")
      )
      test("neg1") - check(
        "doesntExist",
        Result.Failure("Cannot resolve doesntExist. Try `mill resolve _` to see what's available.")
      )
      test("neg2") - check(
        "single.doesntExist",
        Result.Failure(
          "Cannot resolve single.doesntExist. single resolves to a Task with no children."
        )
      )
      test("neg3") - check(
        "nested.doesntExist",
        Result.Failure(
          "Cannot resolve nested.doesntExist. Try `mill resolve nested._` to see what's available."
        )
      )
      test("neg3") - check(
        "nested.singel",
        Result.Failure("Cannot resolve nested.singel. Did you mean nested.single?")
      )
      test("neg4") - check(
        "classInstance.doesntExist",
        Result.Failure(
          "Cannot resolve classInstance.doesntExist. Try `mill resolve classInstance._` to see what's available."
        )
      )
      test("wildcard") - check(
        "_.single",
        Result.Success(Set(
          _.classInstance.single,
          _.nested.single
        )),
        Set("nested.single", "classInstance.single")
      )
      test("wildcardNeg") - check(
        "_._.single",
        Result.Failure(
          "Cannot resolve _._.single. Try `mill resolve _` or `mill resolve __.single` to see what's available."
        )
      )
      test("wildcardNeg2") - check(
        "_._.__",
        Result.Failure("Cannot resolve _._.__. Try `mill resolve _` to see what's available.")
      )
      test("wildcardNeg3") - check(
        "nested._.foobar",
        Result.Failure(
          "Cannot resolve nested._.foobar. nested._ resolves to a Task with no children."
        )
      )
      test("wildcard2") - check(
        "__.single",
        Result.Success(Set(
          _.single,
          _.classInstance.single,
          _.nested.single
        )),
        Set("single", "nested.single", "classInstance.single")
      )

      test("wildcard3") - check(
        "_.__.single",
        Result.Success(Set(
          _.classInstance.single,
          _.nested.single
        )),
        Set("nested.single", "classInstance.single")
      )
      test("wildcard4") - check(
        "__.__.single",
        Result.Success(Set(
          _.classInstance.single,
          _.nested.single,
          _.single
        )),
        Set("nested.single", "classInstance.single", "single")
      )
    }
    test("doubleNested") {
      val check = new Checker(doubleNestedModule)
      test("pos1") - check("single", Result.Success(Set(_.single)), Set("single"))
      test("pos2") - check(
        "nested.single",
        Result.Success(Set(_.nested.single)),
        Set("nested.single")
      )
      test("pos3") - check(
        "nested.inner.single",
        Result.Success(Set(_.nested.inner.single)),
        Set("nested.inner.single")
      )
      test("neg1") - check(
        "doesntExist",
        Result.Failure("Cannot resolve doesntExist. Try `mill resolve _` to see what's available.")
      )
      test("neg2") - check(
        "nested.doesntExist",
        Result.Failure(
          "Cannot resolve nested.doesntExist. Try `mill resolve nested._` to see what's available."
        )
      )
      test("neg3") - check(
        "nested.inner.doesntExist",
        Result.Failure(
          "Cannot resolve nested.inner.doesntExist. Try `mill resolve nested.inner._` to see what's available."
        )
      )
      test("neg4") - check(
        "nested.inner.doesntExist.alsoDoesntExist2",
        Result.Failure(
          "Cannot resolve nested.inner.doesntExist.alsoDoesntExist2. Try `mill resolve nested.inner._` to see what's available."
        )
      )
    }

  }
}
