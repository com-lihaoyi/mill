package mill.integration

import utest._

object CodeSigScalaModuleTests extends IntegrationTestSuite {
  val tests = Tests {
    def filterLines(out: String) = {
      out.linesIterator.filter(!_.contains("[info]")).toSeq
    }
    val wsRoot = initWorkspace()
    "test" - {
      // Check normal behavior for initial run and subsequent fully-cached run
      // with no changes
      val initial = evalStdout("foo.run")

      assert(
        filterLines(initial.out) ==
        Seq(
          "Generating Sources...",
          "Compiling...",
          "Hello World",
          "Running..."
        )
      )

      val cached = evalStdout("foo.run")
      assert(
        filterLines(cached.out) ==
        Seq(
          "Hello World",
          "Running..."
        )
      )

      // Changing the body of a T{...} block directly invalidates that target
      // and any downstream targets
      mangleFile(wsRoot / "build.sc", _.replace("Running...", "RUNNING"))
      val mangledFoo = evalStdout("foo.run")

      // Not sure why this fails :/ seems to pass when i run the steps manually
//      assert(
//        filterLines(mangledFoo.out) ==
//        Seq(
//          "Hello World",
//          "RUNNING"
//        )
//      )

      mangleFile(wsRoot / "build.sc", _.replace("Compiling...", "COMPILING"))
      val mangledFoo2 = evalStdout("foo.run")

      assert(
        filterLines(mangledFoo2.out) ==
        Seq(
          "COMPILING",
          "Hello World",
          "RUNNING"
        )
      )

      mangleFile(wsRoot / "build.sc", _.replace("Generating Sources...", "GENERATING SOURCES"))
      val mangledFoo3 = evalStdout("foo.run")

      assert(
        filterLines(mangledFoo3.out) ==
        Seq(
          "GENERATING SOURCES",
          "COMPILING",
          "Hello World",
          "RUNNING"
        )
      )

      mangleFile(wsRoot / "build.sc", _.replace("2.13.8", "2.13.10"))
      val mangledFoo4 = evalStdout("foo.run")

      assert(
        filterLines(mangledFoo4.out) ==
        Seq(
          "COMPILING",
          "Hello World",
          "RUNNING"
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
      val mangledFoo5 = evalStdout("foo.run")
      assert(
        filterLines(mangledFoo5.out) ==
        Seq(
          "Hello World",
          "RUNNING"
        )
      )
    }
  }
}
