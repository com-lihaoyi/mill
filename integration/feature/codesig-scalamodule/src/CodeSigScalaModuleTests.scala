import mill.testkit.UtestIntegrationTestSuite
import utest.{assert, *}

object CodeSigScalaModuleTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    def filterLines(out: String) = {
      out.linesIterator.filter(!_.contains("[info]")).toSet
    }
    test("single") - integrationTest { tester =>
      import tester.*
      // Tests for fine-grained method-based invalidation within a single ScalaModule

      // Check normal behavior for initial run and subsequent fully-cached run
      // with no changes
      val initial = eval("foo.run")

      assert(
        filterLines(initial.out) ==
          Set(
            "Foo generating sources...",
            "Foo compiling...",
            "Foo Hello World",
            "Foo running..."
          )
      )

      val cached = eval("foo.run")
      assert(
        filterLines(cached.out) ==
          Set(
            "Foo Hello World",
            "Foo running..."
          )
      )

      // Changing the body of a Task{...} block directly invalidates that task
      // and any downstream tasks
      modifyFile(workspacePath / "build.mill", _.replace("Foo running...", "FOO RUNNING"))
      val mangledFoo = eval("foo.run")
      assert(filterLines(mangledFoo.out) == Set("Foo Hello World", "FOO RUNNING"))

      // Changing the body `foo.compile` invalidates `foo.compile`, and downstream
      // `foo.run` runs regardless
      modifyFile(workspacePath / "build.mill", _.replace("Foo compiling...", "FOO COMPILING"))
      val mangledFoo2 = eval("foo.run")

      assert(
        filterLines(mangledFoo2.out) ==
          Set(
            "FOO COMPILING",
            "Foo Hello World",
            "FOO RUNNING"
          )
      )

      // Changing the body `foo.generatedSources` invalidates `foo.generatedSources`,
      // but if the return value is not changed then `foo.compile` does not invalidate
      modifyFile(
        workspacePath / "build.mill",
        _.replace("Foo generating sources...", "FOO GENERATING SOURCES")
      )
      val mangledFoo3 = eval("foo.run")

      assert(
        filterLines(mangledFoo3.out) ==
          Set(
            "FOO GENERATING SOURCES",
            "Foo Hello World",
            "FOO RUNNING"
          )
      )

      // Changing the body `foo.generatedSources` invalidates `foo.generatedSources`,
      // and if the return value is changed then `foo.compile` does invalidate
      modifyFile(workspacePath / "build.mill", _.replace(""""Hello World"""", """"HELLO WORLD""""))
      val mangledFoo4 = eval("foo.run")

      assert(
        filterLines(mangledFoo4.out) ==
          Set(
            "FOO GENERATING SOURCES",
            "FOO COMPILING",
            "Foo HELLO WORLD",
            "FOO RUNNING"
          )
      )

      modifyFile(workspacePath / "build.mill", _.replace("2.13.16", "2.12.18"))
      val mangledFoo5 = eval("foo.run")

      assert(
        filterLines(mangledFoo5.out) ==
          Set(
            "FOO COMPILING",
            "Foo HELLO WORLD",
            "FOO RUNNING"
          )
      )

      // Adding newlines in various places doesn't invalidate anything
      // (preserving the indentation to avoid warnings in Scala 3).
      modifyFile(
        workspacePath / "build.mill",
        s =>
          "\n\n\n" +
            s.replace("\n  def scalaVersion", "\n\n  def scalaVersion")
              .replace("\n  def sources = T{\n", "\n\n  def sources = T{\n\n")
              .replace("\n  def compile = Task {\n", "\n\n  def compile = Task {\n\n")
              .replace(
                "\n  def run(args: Task[Args] = Task.task(Args())) = Task.command {\n",
                "\n\n  def run(args: Task[Args] = Task.task(Args())) = Task.command {\n\n"
              )
      )
      val mangledFoo6 = eval("foo.run")
      assert(
        filterLines(mangledFoo6.out) ==
          Set(
            "Foo HELLO WORLD",
            "FOO RUNNING"
          )
      )
    }

  }
}

object CodeSigScalaModuleMultipleTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    def filterLines(out: String) = {
      out.linesIterator.filter(!_.contains("[info]")).toSet
    }
    test("multiple") - integrationTest { tester =>
      import tester.*
      // Tests for fine-grained method-based invalidation between multiple ScalaModules,
      // some related and some not

      // Check normal behavior for initial run and subsequent fully-cached run
      // with no changes
      val initial = eval("{foo,bar,qux}.assembly")

      assert(
        filterLines(initial.out) ==
          Set(
            "Foo generating sources...",
            "Foo compiling...",
            "Foo assembly...",
            "Bar generating sources...",
            "Bar compiling...",
            "Bar assembly...",
            "Qux generating sources...",
            "Qux compiling...",
            "Qux assembly..."
          )
      )

      val cached = eval("{foo,bar,qux}.assembly")
      assert(filterLines(cached.out) == Set())

      // Changing the implementation of foo.compile or foo.generatedSources
      // without changing its return value causes that specific task to
      // invalidate, but does not cause downstream tasks to invalidate.
      //
      // This is because the callgraph analyzer ignores calls from the downstream
      // task methods to the upstream task method because it will get handled
      // later by the runtime build graph evaluation, and the runtime build graph
      // evaluation can see the return value was not changed and avoid invalidation
      modifyFile(workspacePath / "build.mill", _.replace("Foo compiling...", "FOO COMPILING"))
      val mangledFoo2 = eval("{foo,bar,qux}.assembly")
      assert(filterLines(mangledFoo2.out) == Set("FOO COMPILING"))

      modifyFile(
        workspacePath / "build.mill",
        _.replace("Foo generating sources...", "FOO generating sources")
      )
      val mangledFoo3 = eval("{foo,bar,qux}.assembly")
      assert(filterLines(mangledFoo3.out) == Set("FOO generating sources"))

      // Changing the implementation of foo.generatedSources in a way that changes
      // its return value does cause downstream tasks in foo and bar to invalidate,
      // but unrelated tasks (bar.generatedSources and qux.*) are not invalidated
      modifyFile(
        workspacePath / "build.mill",
        _.replace("""fooMsg = "Hello World"""", """fooMsg = "HELLO WORLD"""")
      )
      val mangledFoo4 = eval("{foo,bar,qux}.assembly")

      assert(
        filterLines(mangledFoo4.out) ==
          Set(
            "FOO generating sources",
            "FOO COMPILING",
            "Foo assembly...",
            "Bar compiling...",
            "Bar assembly..."
          )
      )
    }

  }
}
