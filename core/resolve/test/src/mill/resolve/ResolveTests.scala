package mill.resolve

import mill.api.Result
import mill.api.Discover
import mill.api.TestGraphs.*
import mill.testkit.TestRootModule
import mill.{Cross, Module, Task}
import utest.*
object ResolveTests extends TestSuite {

  object doubleNestedModule extends TestRootModule {
    def single = Task { 5 }
    object nested extends Module {
      def single = Task { 7 }

      object inner extends Module {
        def single = Task { 9 }
      }
    }
    lazy val millDiscover = Discover[this.type]
  }

  // Test module with task overrides for super task resolution
  trait BaseModuleTrait extends Module {
    def foo = Task { Seq("base") }
  }

  object superTaskModule extends TestRootModule with BaseModuleTrait {
    override def foo = Task { super.foo() ++ Seq("override") }
    lazy val millDiscover = Discover[this.type]
  }

  // Test module with nested module containing override
  object nestedSuperTaskModule extends TestRootModule {
    trait Inner extends Module {
      def bar = Task { "inner" }
    }
    object nested extends Inner {
      override def bar = Task { super.bar() + "-override" }
    }
    lazy val millDiscover = Discover[this.type]
  }

  // Test module with multiple levels of overrides
  trait TraitA extends Module {
    def multi = Task { 1 }
  }
  trait TraitB extends TraitA {
    override def multi = Task { super.multi() + 10 }
  }
  object multiLevelSuperTask extends TestRootModule with TraitB {
    override def multi = Task { super.multi() + 100 }
    lazy val millDiscover = Discover[this.type]
  }

  // Test module with @Task.rename annotation
  object renameModule extends TestRootModule {
    def normalTask = Task { "normal" }

    // This task is named `renamedTaskImpl` in code but exposed as `renamedTask` on CLI
    @Task.rename("renamedTask")
    def renamedTaskImpl = Task { "renamed" }

    object nested extends Module {
      @Task.rename("shortName")
      def veryLongTaskName = Task { "nested-renamed" }
    }

    lazy val millDiscover = Discover[this.type]
  }

  // Test module for private task visibility
  object privateMethodsModule extends TestRootModule {
    def pub = Task { priv() }
    private def priv = Task { "priv" }

    object foo extends Module {
      def bar = Task { baz() }
    }
    private def baz = Task { "bazOuter" }

    object qux extends Module {
      object foo extends Module {
        def bar = Task { baz() }
      }
      private def baz = Task { "bazInner" }
    }

    object cls extends clsClass
    class clsClass extends Module {
      object foo extends Module {
        def bar = Task { baz() }
      }
      private def baz = Task { "bazCls" }
    }

    lazy val millDiscover = Discover[this.type]
  }

  // Test that modules named with Scala keywords work
  object keywordModule extends TestRootModule {
    object `for` extends Module {
      def task = Task { "for" }
    }
    object `if` extends Module {
      def task = Task { "if" }
    }
    object `import` extends Module {
      def task = Task { "import" }
    }
    object `null` extends Module {
      def task = Task { "null" }
    }
    object `this` extends Module {
      def task = Task { "this" }
    }

    lazy val millDiscover = Discover[this.type]
  }

  // Test cross modules
  object crossModule extends TestRootModule {
    object myCross extends Cross[MyCross]("a", "b")
    trait MyCross extends Cross.Module[String] {
      def myTask = Task { crossValue }
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
          "Task selector must not be empty. Try `mill resolve _` to see what's available."
        )
      )
    }
    test("backtickIdentifiers") {
      val check = new Checker(bactickIdentifiers)
      test("pos1") - check("up-task", Result.Success(Set(_.`up-task`)), Set("up-task"))
      test("pos2") - check(
        "a-down-task",
        Result.Success(Set(_.`a-down-task`)),
        Set("a-down-task")
      )
      test("neg1") - check(
        "uptask",
        Result.Failure("Cannot resolve uptask. Did you mean up-task?")
      )
      test("neg2") - check(
        "upt-ask",
        Result.Failure("Cannot resolve upt-ask. Did you mean up-task?")
      )
      test("neg3") - check(
        "up-task.doesntExist",
        Result.Failure(
          "Cannot resolve up-task.doesntExist. up-task resolves to a Task with no children."
        )
      )
      test("neg4") - check(
        "",
        Result.Failure(
          "Task selector must not be empty. Try `mill resolve _` to see what's available."
        )
      )
      test("neg5") - check(
        "invisible",
        Result.Failure(
          "Cannot resolve invisible. Try `mill resolve _`, `mill resolve __.invisible&` to see what's available, or `mill __.invisible&` to run all `invisible&` tasks"
        )
      )
      test("negBadParse") - check(
        "invisible&",
        Result.Failure("Parsing exception Position 1:10, found \"&\"")
      )
      test("nested") {
        test("pos") - check(
          "nested-module.nested-task",
          Result.Success(Set(_.`nested-module`.`nested-task`)),
          Set("nested-module.nested-task")
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
          "Cannot resolve _._.single. Try `mill resolve _`, `mill resolve __.single` to see what's available, or `mill __.single` to run all `single` tasks"
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

    test("superTask") {
      test("singleOverride") {
        // Test resolving super task with single override
        val check = new Checker(superTaskModule)

        // The super task should be resolvable via foo.super.BaseModuleTrait
        // Segments.render uses "." to join segments
        test("resolveWithSuffix") - check.checkSeq0(
          Seq("foo.super.BaseModuleTrait"),
          { result =>
            result.isInstanceOf[Result.Success[?]] &&
            result.toOption.get.size == 1 &&
            result.toOption.get.head.ctx.segments.render == "foo.super.BaseModuleTrait"
          }
        )

        // Without suffix, should work since there's only one super task
        test("resolveWithoutSuffix") - check.checkSeq0(
          Seq("foo.super"),
          { result =>
            result.isInstanceOf[Result.Success[?]] &&
            result.toOption.get.size == 1 &&
            result.toOption.get.head.ctx.segments.render == "foo.super.BaseModuleTrait"
          }
        )
      }

      test("nestedModuleOverride") {
        // Test resolving super task in nested module
        val check = new Checker(nestedSuperTaskModule)

        test("resolveNestedSuper") - check.checkSeq0(
          Seq("nested.bar.super.Inner"),
          { result =>
            result.isInstanceOf[Result.Success[?]] &&
            result.toOption.get.size == 1 &&
            result.toOption.get.head.ctx.segments.render == "nested.bar.super.Inner"
          }
        )
      }

      test("multiLevelOverride") {
        // Test resolving super tasks with multiple levels of overrides
        val check = new Checker(multiLevelSuperTask)

        // Should resolve to TraitA's version
        test("resolveTraitA") - check.checkSeq0(
          Seq("multi.super.TraitA"),
          { result =>
            result.isInstanceOf[Result.Success[?]] &&
            result.toOption.get.size == 1 &&
            result.toOption.get.head.ctx.segments.render == "multi.super.TraitA"
          }
        )

        // Should resolve to TraitB's version
        test("resolveTraitB") - check.checkSeq0(
          Seq("multi.super.TraitB"),
          { result =>
            result.isInstanceOf[Result.Success[?]] &&
            result.toOption.get.size == 1 &&
            result.toOption.get.head.ctx.segments.render == "multi.super.TraitB"
          }
        )

        // Without suffix should show ambiguous error since there are multiple super tasks
        test("ambiguousWithoutSuffix") - check.checkSeq0(
          Seq("multi.super"),
          { result =>
            result.isInstanceOf[Result.Failure] &&
            result.errorOpt.exists(_.contains("Ambiguous super task reference"))
          }
        )
      }

      test("noSuperTask") {
        // Test that resolving super on a non-overridden task fails
        val check = new Checker(singleton)
        test("failsOnNonOverriddenTask") - check(
          "single.super",
          Result.Failure(
            "Task single has no super tasks. Only overridden tasks have super tasks that can be invoked."
          )
        )
      }
    }

    test("rename") {
      val check = new Checker(renameModule)

      test("normalTaskStillWorks") - check(
        "normalTask",
        Result.Success(Set(_.normalTask)),
        Set("normalTask")
      )

      test("renamedTaskResolvesViaNewName") - check(
        "renamedTask",
        Result.Success(Set(_.renamedTaskImpl)),
        Set("renamedTask")
      )

      test("originalNameNotResolvable") - check.checkSeq0(
        Seq("renamedTaskImpl"),
        result => result.isInstanceOf[Result.Failure]
      )

      test("nestedRenameWorks") - check(
        "nested.shortName",
        Result.Success(Set(_.nested.veryLongTaskName)),
        Set("nested.shortName")
      )

      test("nestedOriginalNameNotResolvable") - check.checkSeq0(
        Seq("nested.veryLongTaskName"),
        result => result.isInstanceOf[Result.Failure]
      )

      test("wildcardIncludesRenamedTasks") - check.checkSeq0(
        Seq("_"),
        result => {
          result.isInstanceOf[Result.Success[?]] &&
          result.toOption.get.size == 2 // normalTask and renamedTaskImpl (renamed as renamedTask)
        },
        metadata => {
          metadata.isInstanceOf[Result.Success[?]] &&
          metadata.toOption.get.toSet.contains("renamedTask") &&
          metadata.toOption.get.toSet.contains("normalTask")
        }
      )
    }

    test("privateMethods") {
      val check = new Checker(privateMethodsModule)

      // Simple public task depending on private task works
      test("pubDependsOnPriv") - check(
        "pub",
        Result.Success(Set(_.pub)),
        Set("pub")
      )

      // Calling private methods indirectly via public tasks works
      test("fooBar") - check(
        "foo.bar",
        Result.Success(Set(_.foo.bar)),
        Set("foo.bar")
      )

      test("quxFooBar") - check(
        "qux.foo.bar",
        Result.Success(Set(_.qux.foo.bar)),
        Set("qux.foo.bar")
      )

      test("clsFooBar") - check(
        "cls.foo.bar",
        Result.Success(Set(_.cls.foo.bar)),
        Set("cls.foo.bar")
      )

      // Calling private methods directly fails
      test("privNotResolvable") - check.checkSeq0(
        Seq("priv"),
        result => result.isInstanceOf[Result.Failure] &&
          result.errorOpt.exists(_.contains("Cannot resolve priv"))
      )

      test("bazNotResolvable") - check.checkSeq0(
        Seq("baz"),
        result => result.isInstanceOf[Result.Failure] &&
          result.errorOpt.exists(_.contains("Cannot resolve baz"))
      )

      test("quxBazNotResolvable") - check.checkSeq0(
        Seq("qux.baz"),
        result => result.isInstanceOf[Result.Failure] &&
          result.errorOpt.exists(_.contains("Cannot resolve qux.baz"))
      )

      test("clsBazNotResolvable") - check.checkSeq0(
        Seq("cls.baz"),
        result => result.isInstanceOf[Result.Failure] &&
          result.errorOpt.exists(_.contains("Cannot resolve cls.baz"))
      )
    }

    test("keywordModules") {
      val check = new Checker(keywordModule)

      // Test that modules named with Scala keywords can be resolved
      test("for") - check(
        "for.task",
        Result.Success(Set(_.`for`.task)),
        Set("for.task")
      )

      test("if") - check(
        "if.task",
        Result.Success(Set(_.`if`.task)),
        Set("if.task")
      )

      test("import") - check(
        "import.task",
        Result.Success(Set(_.`import`.task)),
        Set("import.task")
      )

      test("null") - check(
        "null.task",
        Result.Success(Set(_.`null`.task)),
        Set("null.task")
      )

      test("this") - check(
        "this.task",
        Result.Success(Set(_.`this`.task)),
        Set("this.task")
      )
    }

    test("crossModules") {
      val check = new Checker(crossModule)

      // Test cross modules can be resolved
      test("crossA") - check(
        "myCross[a].myTask",
        Result.Success(Set(_.myCross("a").myTask)),
        Set("myCross.a.myTask")
      )

      test("crossB") - check(
        "myCross[b].myTask",
        Result.Success(Set(_.myCross("b").myTask)),
        Set("myCross.b.myTask")
      )

      // Test wildcard across cross values
      test("crossWildcard") - check(
        "myCross._.myTask",
        Result.Success(Set(_.myCross("a").myTask, _.myCross("b").myTask)),
        Set("myCross.a.myTask", "myCross.b.myTask")
      )
    }

  }
}
