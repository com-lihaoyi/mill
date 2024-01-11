package mill.integration

import utest._

object CodeSigScalaModuleTests extends IntegrationTestSuite {
  val tests: Tests = Tests {
    def filterLines(out: String) = {
      out.linesIterator.filter(!_.contains("[info]")).toSeq
    }
    val wsRoot = initWorkspace()
    "single" - {
      // Tests for fine-grained method-based invalidation within a single ScalaModule

      // Check normal behavior for initial run and subsequent fully-cached run
      // with no changes
      val initial = evalStdout("foo.run")

      assert(
        filterLines(initial.out) ==
          Seq(
            "Foo generating sources...",
            "Foo compiling...",
            "Foo Hello World",
            "Foo running..."
          )
      )

      val cached = evalStdout("foo.run")
      assert(
        filterLines(cached.out) ==
          Seq(
            "Foo Hello World",
            "Foo running..."
          )
      )

      // Changing the body of a T{...} block directly invalidates that target
      // and any downstream targets
      mangleFile(wsRoot / "build.sc", _.replace("Foo running...", "FOO RUNNING"))
      val mangledFoo = evalStdout("foo.run")
      assert(filterLines(mangledFoo.out) == Seq("Foo Hello World", "FOO RUNNING"))

      // Changing the body `foo.compile` invalidates `foo.compile`, and downstream
      // `foo.run` runs regardless
      mangleFile(wsRoot / "build.sc", _.replace("Foo compiling...", "FOO COMPILING"))
      val mangledFoo2 = evalStdout("foo.run")

      assert(
        filterLines(mangledFoo2.out) ==
          Seq(
            "FOO COMPILING",
            "Foo Hello World",
            "FOO RUNNING"
          )
      )

      // Changing the body `foo.generatedSources` invalidates `foo.generatedSources`,
      // but if the return value is not changed then `foo.compile` does not invalidate
      mangleFile(
        wsRoot / "build.sc",
        _.replace("Foo generating sources...", "FOO GENERATING SOURCES")
      )
      val mangledFoo3 = evalStdout("foo.run")

      assert(
        filterLines(mangledFoo3.out) ==
          Seq(
            "FOO GENERATING SOURCES",
            "Foo Hello World",
            "FOO RUNNING"
          )
      )

      // Changing the body `foo.generatedSources` invalidates `foo.generatedSources`,
      // and if the return value is changed then `foo.compile` does invalidate
      mangleFile(wsRoot / "build.sc", _.replace(""""Hello World"""", """"HELLO WORLD""""))
      val mangledFoo4 = evalStdout("foo.run")

      assert(
        filterLines(mangledFoo4.out) ==
          Seq(
            "FOO GENERATING SOURCES",
            "FOO COMPILING",
            "Foo HELLO WORLD",
            "FOO RUNNING"
          )
      )

      mangleFile(wsRoot / "build.sc", _.replace("2.13.8", "2.12.11"))
      val mangledFoo5 = evalStdout("foo.run")

      assert(
        filterLines(mangledFoo5.out) ==
          Seq(
            "FOO COMPILING",
            "Foo HELLO WORLD",
            "FOO RUNNING"
          )
      )

      // Adding newlines in various places doesn't invalidate anything
      mangleFile(
        wsRoot / "build.sc",
        s =>
          "\n\n\n" +
            s.replace("def scalaVersion", "\ndef scalaVersion\n")
              .replace("def sources", "\ndef sources\n")
              .replace("def compile", "\ndef compile\n")
              .replace("def run", "\ndef run\n")
      )
      val mangledFoo6 = evalStdout("foo.run")
      assert(
        filterLines(mangledFoo6.out) ==
          Seq(
            "Foo HELLO WORLD",
            "FOO RUNNING"
          )
      )
    }

    "multiple" - {
      // Tests for fine-grained method-based invalidation between multiple ScalaModules,
      // some related and some not

      // Check normal behavior for initial run and subsequent fully-cached run
      // with no changes
      val initial = evalStdout("{foo,bar,qux}.assembly")

      assert(
        filterLines(initial.out) ==
          Seq(
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

      val cached = evalStdout("{foo,bar,qux}.assembly")
      assert(filterLines(cached.out) == Seq())

      // Changing the implementation of foo.compile or foo.generatedSources
      // without changing its return value causes that specific target to
      // invalidate, but does not cause downstream targets to invalidate.
      //
      // This is because the callgraph analyzer ignores calls from the downstream
      // target methods to the upstream target method because it will get handled
      // later by the runtime build graph evaluation, and the runtime build graph
      // evaluation can see the return value was not changed and avoid invalidation
      mangleFile(wsRoot / "build.sc", _.replace("Foo compiling...", "FOO COMPILING"))
      val mangledFoo2 = evalStdout("{foo,bar,qux}.assembly")
      assert(filterLines(mangledFoo2.out) == Seq("FOO COMPILING"))

      mangleFile(
        wsRoot / "build.sc",
        _.replace("Foo generating sources...", "FOO generating sources")
      )
      val mangledFoo3 = evalStdout("{foo,bar,qux}.assembly")
      assert(filterLines(mangledFoo3.out) == Seq("FOO generating sources"))

      // Changing the implementation of foo.generatedSources in a way that changes
      // its return value does cause downstream targets in foo and bar to invalidate,
      // but unrelated targets (bar.generatedSources and qux.*) are not invalidated
      mangleFile(
        wsRoot / "build.sc",
        _.replace("""fooMsg = "Hello World"""", """fooMsg = "HELLO WORLD"""")
      )
      val mangledFoo4 = evalStdout("{foo,bar,qux}.assembly")

      assert(
        filterLines(mangledFoo4.out) ==
          Seq(
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
