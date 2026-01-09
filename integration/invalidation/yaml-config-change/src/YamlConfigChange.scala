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

    test("mvnDepsAppend") - integrationTest { tester =>
      import tester.*

      // Get baseline mvnDeps (should be empty for plain JavaModule)
      val baseline = eval(("show", "mvnDeps"))
      assert(baseline.isSuccess)
      val baselineDeps = baseline.out.trim
      assert(baselineDeps == "[]")

      // Add first dependency with !append
      modifyFile(
        workspacePath / "build.mill.yaml",
        _ + "\nmvnDeps: !append\n- com.lihaoyi:mainargs_2.13:0.4.0"
      )
      val withFirstDep = eval(("show", "mvnDeps"))
      assert(withFirstDep.isSuccess)
      assert(withFirstDep.out.contains("com.lihaoyi:mainargs_2.13:0.4.0"))

      // Add second dependency to the !append list
      modifyFile(
        workspacePath / "build.mill.yaml",
        _.replace(
          "mvnDeps: !append\n- com.lihaoyi:mainargs_2.13:0.4.0",
          "mvnDeps: !append\n- com.lihaoyi:mainargs_2.13:0.4.0\n- com.lihaoyi:os-lib_2.13:0.9.1"
        )
      )
      val withBothDeps = eval(("show", "mvnDeps"))
      assert(withBothDeps.isSuccess)
      assert(withBothDeps.out.contains("com.lihaoyi:mainargs_2.13:0.4.0"))
      assert(withBothDeps.out.contains("com.lihaoyi:os-lib_2.13:0.9.1"))

      // Remove mvnDeps entirely - should go back to baseline
      modifyFile(
        workspacePath / "build.mill.yaml",
        _.replace(
          "\nmvnDeps: !append\n- com.lihaoyi:mainargs_2.13:0.4.0\n- com.lihaoyi:os-lib_2.13:0.9.1",
          ""
        )
      )
      val backToBaseline = eval(("show", "mvnDeps"))
      assert(backToBaseline.isSuccess)
      assert(backToBaseline.out.trim == baselineDeps)
    }
  }
}
