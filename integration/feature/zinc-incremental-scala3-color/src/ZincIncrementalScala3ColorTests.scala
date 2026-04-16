package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest.*

// Regression test for issue https://github.com/com-lihaoyi/mill/issues/7015
//
// On Scala 3, ZincWorker conditionally prepends `-color:never` to scalacOptions
// based on the current client's `colored` flag. That option is baked into
// Zinc's MiniSetup, so alternating clients with different TTY state (e.g. a
// non-TTY CI run followed by an interactive shell) makes the MiniSetup
// comparison fail on the next edit and cold-rebuilds every source in the
// module. Here we simulate the two clients with `--color=false` / `--color=true`
// and assert that sources which were NOT edited are not recompiled.
object ZincIncrementalScala3ColorTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test("colorFlagPreservesIncremental") - integrationTest { tester =>
      import tester.*

      val appSrc = workspacePath / "app/src/App.scala"
      val classes = workspacePath / "out/app/compile.dest/classes"
      val appClass = classes / "app/App.class"
      val fooClass = classes / "models/Foo.class"

      // Client B (non-TTY): cold compile with colored=false.
      // MiniSetup gets baked with `-color:never` in scalacOptions.
      val firstRun = tester.eval(("--color=false", "app.compile"))
      assert(firstRun.isSuccess)
      assert(Seq(classes, appClass, fooClass, appSrc).forall(os.exists))

      val appClassInfo1 = os.stat(appClass)
      val fooClassInfo1 = os.stat(fooClass)

      // Edit only App.scala. Foo.scala is untouched.
      os.write.append(appSrc, "\n ")

      // Client T (TTY): compile with colored=true.
      // Worker builds scalacOptions WITHOUT `-color:never`, hands them to
      // Zinc. Zinc compares against stored MiniSetup (with `-color:never`),
      // declares a mismatch, and cold-recompiles every source — including
      // Foo.scala, which was not edited.
      val secondRun = tester.eval(("--color=true", "app.compile"))
      assert(secondRun.isSuccess)

      val appClassInfo2 = os.stat(appClass)
      val fooClassInfo2 = os.stat(fooClass)

      // App.scala was edited, so its .class is expected to be rewritten.
      // We use mtime instead of ctime because Scala 3 compile is fast enough
      // that both runs can land inside the same wall-clock second, and
      // os-lib's ctime formatter is second-granularity; mtime has nanosecond
      // precision.
      assert(appClassInfo1.mtime != appClassInfo2.mtime)

      // Foo.scala was NOT edited. With a correct incremental compile this
      // .class should be untouched. This assertion currently FAILS and is
      // the reproduction of issue #7015 — Zinc cold-rebuilds Foo.class
      // because of the MiniSetup mismatch caused by the `-color:never`
      // injection being tied to per-client TTY state.
      assert(fooClassInfo1.mtime == fooClassInfo2.mtime)
    }
  }
}
