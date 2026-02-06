package mill.integration

import mill.testkit.UtestIntegrationTestSuite
import utest.asserts.{RetryInterval, RetryMax}
import scala.concurrent.duration.*
import utest.*

// Make sure that changes in `build.mill.yaml` configs and related
// sources properly trigger selective execution and cache invalidation
object YamlConfigChange extends UtestIntegrationTestSuite {
  implicit val retryMax: RetryMax = RetryMax(120.seconds)
  implicit val retryInterval: RetryInterval = RetryInterval(1.seconds)
  val tests: Tests = Tests {
    test("extendsChange") - integrationTest { tester =>
      import tester.*

      val firstCompile = eval(("compile"))
      assert(firstCompile.isSuccess)

      modifyFile(
        workspacePath / "build.mill.yaml",
        _ =>
          """extends:
            |- ScalaModule
            |- SbtModule
            |scalaVersion: 3.3.3
            |""".stripMargin
      )

      val showScalaVersion = eval(("show", "scalaVersion"))
      assert(showScalaVersion.isSuccess)
      assert(showScalaVersion.out.contains("3.3.3"))
    }

    test("nestedExtendsChange") - integrationTest { tester =>
      import tester.*

      val firstCompile = eval(("__.compile"))
      assert(firstCompile.isSuccess)

      modifyFile(
        workspacePath / "foo/bar/package.mill.yaml",
        _ =>
          """extends: ScalaModule
            |scalaVersion: 3.3.3
            |""".stripMargin
      )

      val secondCompile = eval(("__.compile"))
      assert(secondCompile.isSuccess)

      val showScalaVersion = eval(("show", "foo.bar.scalaVersion"))
      assert(showScalaVersion.isSuccess)
      assert(showScalaVersion.out.contains("3.3.3"))
    }

    test("sources") - integrationTest { tester =>
      val spawned = tester.spawn(("--watch", "run"))

      assertEventually(spawned.out.text().contains("Hello 1"))

      // Normal changes within the source folder are picked up
      tester.modifyFile(tester.workspacePath / "src/Foo.java", _.replace("Hello 1", "HELLO 1"))
      assertEventually(spawned.out.text().contains("HELLO 1"))

      // Changes to the `build.mill.yaml` to set a custom source folder are picked up
      tester.modifyFile(tester.workspacePath / "build.mill.yaml", _ + "\nsources: [src-2/]")
      assertEventually(spawned.out.text().contains("Hello 2"))

      // Changes to files within the alternate source folder are picked up
      tester.modifyFile(tester.workspacePath / "src-2/Foo.java", _.replace("Hello 2", "HELLO 2"))
      assertEventually(spawned.out.text().contains("HELLO 2"))

      // Changes from one custom source folder to another are picked up
      tester.modifyFile(
        tester.workspacePath / "build.mill.yaml",
        _.replace("[src-2/]", "[src-3/]")
      )
      assertEventually(spawned.out.text().contains("Hello 3"))
    }

    test("append") - integrationTest { tester =>
      import tester.*

      // Get baseline sources (should be default src/)
      val baseline = eval(("show", "sources"))
      assert(baseline.isSuccess)
      assert(
        baseline.out.contains("\"src\"") ||
          baseline.out.contains("/src\"") ||
          baseline.out.contains("\\src\"")
      )

      // Set normal sources value (replaces default)
      modifyFile(workspacePath / "build.mill.yaml", _ + "\nsources: [src-2/]")
      val withNormal = eval(("show", "sources"))
      assert(withNormal.isSuccess)
      assert(withNormal.out.contains("src-2"))
      assert(!withNormal.out.contains("\"src\""))

      // Transition from normal to !append: now should have default src/ AND src-extra/
      modifyFile(
        workspacePath / "build.mill.yaml",
        _.replace("sources: [src-2/]", "sources: !append\n- src-extra/")
      )
      val withAppend = eval(("show", "sources"))
      assert(withAppend.isSuccess)
      // With !append, should have both default src/ and appended src-extra/
      assert(withAppend.out.contains("src-extra"))
      assert(
        withAppend.out.contains("\"src\"") ||
          withAppend.out.contains("/src\"") ||
          withAppend.out.contains("\\src\"")
      )

      // Add another folder to the !append list
      modifyFile(
        workspacePath / "build.mill.yaml",
        _.replace("sources: !append\n- src-extra/", "sources: !append\n- src-extra/\n- src-2/")
      )
      val withMoreAppend = eval(("show", "sources"))
      assert(withMoreAppend.isSuccess)
      assert(
        withMoreAppend.out.contains("\"src\"") ||
          withMoreAppend.out.contains("/src\"") ||
          withMoreAppend.out.contains("\\src\"")
      )
      assert(withMoreAppend.out.contains("src-extra"))
      assert(withMoreAppend.out.contains("src-2"))

      // Transition from !append back to normal value
      modifyFile(
        workspacePath / "build.mill.yaml",
        _.replace("sources: !append\n- src-extra/\n- src-2/", "sources: [src-3/]")
      )
      val backToNormal = eval(("show", "sources"))
      assert(backToNormal.isSuccess)
      assert(backToNormal.out.contains("src-3"))
      assert(!backToNormal.out.contains("\"src\""))

      // Remove sources entirely - should go back to default
      modifyFile(
        workspacePath / "build.mill.yaml",
        _.replace("\nsources: [src-3/]", "")
      )
      val backToDefault = eval(("show", "sources"))
      assert(backToDefault.isSuccess)
      assert(
        backToDefault.out.contains("\"src\"") ||
          backToDefault.out.contains("/src\"") ||
          backToDefault.out.contains("\\src\"")
      )
    }

    test("moduleDeps") - integrationTest { tester =>
      import tester.*

      // String to check for Scala compilation - used to verify compilation happens
      // initially and does NOT happen when only moduleDeps config changes
      val compilingScala = "compiling"

      // Compile root module first so its classes exist
      val compileRoot = eval(("compile"))
      assert(compileRoot.isSuccess)
      // Verify compilation actually happened so we know the string check is valid
      assert(compileRoot.err.contains(compilingScala))

      // Get baseline compileClasspath for sub module (no moduleDeps yet)
      val baseline = eval(("show", "sub.compileClasspath"))
      assert(baseline.isSuccess)
      assert(!baseline.out.replace("\\\\", "/").contains("/compile.dest/classes"))

      // Add moduleDeps on root module
      modifyFile(workspacePath / "sub/package.mill.yaml", _ + "\nmoduleDeps: [build]")
      val withModuleDeps = eval(("show", "sub.compileClasspath"))
      assert(withModuleDeps.isSuccess)
      assert(withModuleDeps.out.replace("\\\\", "/").contains("/compile.dest/classes"))
      // Changing moduleDeps should NOT trigger Scala compilation of the build
      assert(!withModuleDeps.err.contains(compilingScala))

      // Remove moduleDeps - should no longer include root module's classes
      modifyFile(
        workspacePath / "sub/package.mill.yaml",
        _.replace("\nmoduleDeps: [build]", "")
      )
      val withoutModuleDeps = eval(("show", "sub.compileClasspath"))
      assert(withoutModuleDeps.isSuccess)
      assert(!withoutModuleDeps.out.replace("\\\\", "/").contains("/compile.dest/classes"))
      assert(!withoutModuleDeps.err.contains(compilingScala))

      // Test compileModuleDeps
      modifyFile(workspacePath / "sub/package.mill.yaml", _ + "\ncompileModuleDeps: [build]")
      val withCompileModuleDeps = eval(("show", "sub.compileClasspath"))
      assert(withCompileModuleDeps.isSuccess)
      assert(withCompileModuleDeps.out.replace("\\\\", "/").contains("/compile.dest/classes"))
      assert(!withCompileModuleDeps.err.contains(compilingScala))

      // compileModuleDeps should NOT appear in runClasspath (check for root module's classes specifically)
      val runWithCompileModuleDeps = eval(("show", "sub.runClasspath"))
      assert(runWithCompileModuleDeps.isSuccess)
      // Root module's classes are at out/compile.dest/classes (not out/sub/compile.dest/classes)
      assert(!runWithCompileModuleDeps.out.replace(
        "\\\\",
        "/"
      ).contains("out/compile.dest/classes\""))

      // Switch to runModuleDeps
      modifyFile(
        workspacePath / "sub/package.mill.yaml",
        _.replace("\ncompileModuleDeps: [build]", "\nrunModuleDeps: [build]")
      )

      // runModuleDeps should NOT appear in compileClasspath (check for root module's classes specifically)
      val compileWithRunModuleDeps = eval(("show", "sub.compileClasspath"))
      assert(compileWithRunModuleDeps.isSuccess)
      assert(!compileWithRunModuleDeps.out.replace(
        "\\\\",
        "/"
      ).contains("out/compile.dest/classes\""))
      assert(!compileWithRunModuleDeps.err.contains(compilingScala))

      // runModuleDeps should appear in runClasspath
      val runWithRunModuleDeps = eval(("show", "sub.runClasspath"))
      assert(runWithRunModuleDeps.isSuccess)
      assert(runWithRunModuleDeps.out.replace("\\\\", "/").contains("out/compile.dest/classes\""))

      // Test circular dependency detection: create a cycle between root and sub
      // First, clear sub's runModuleDeps
      modifyFile(
        workspacePath / "sub/package.mill.yaml",
        _.replace("\nrunModuleDeps: [build]", "")
      )
      // Add moduleDeps: [sub] to root module
      modifyFile(workspacePath / "build.mill.yaml", _ + "\nmoduleDeps: [sub]")
      // Add moduleDeps: [build] to sub module (creates cycle: build -> sub -> build)
      modifyFile(workspacePath / "sub/package.mill.yaml", _ + "\nmoduleDeps: [build]")

      // Evaluating should produce a cycle detection error
      val withCycle = eval(("show", "sub.compileClasspath"))
      assert(!withCycle.isSuccess)
      assert(withCycle.err.contains("cycle detected"))
    }
  }
}
