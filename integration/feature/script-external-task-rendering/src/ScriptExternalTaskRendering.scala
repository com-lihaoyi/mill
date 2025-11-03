package mill.integration

import mill.testkit.UtestIntegrationTestSuite
import utest._

// Make sure ScriptModules and ExternalModules which are not part of the `build.mill`
// module hierarchy have their tasks rendered with the appropriate prefix so they are
// distinct and copy-pasteable.
object ScriptExternalTaskRendering extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test("test") - integrationTest { tester =>
      import tester._

      val resolveRun = eval(("resolve", "Script.java:run"))
      assert(resolveRun.out.linesIterator.toSeq == Seq("./Script.java:run"))

      val resolveAll = eval(("resolve", "Script.java:_"))
      assert(resolveAll.out.linesIterator.toSeq.contains("./Script.java:run"))
      assert(resolveAll.out.linesIterator.toSeq.contains("./Script.java:compile"))

      val planMvnDeps = eval(("plan", "Script.java:mvnDeps"))
      assert(planMvnDeps.out.linesIterator.toSeq == Seq("./Script.java:mvnDeps"))

      val planRun = eval(("plan", "Script.java:run"))
      assert(planRun.out.linesIterator.toSeq.contains("./Script.java:run"))
      assert(planRun.out.linesIterator.toSeq.contains("./Script.java:compile"))

      val resolveScript = eval(("resolve", "Script.java"))
      assert(resolveScript.out.linesIterator.toSeq.contains("./Script.java"))

      val planScript = eval(("plan", "Script.java"))
      assert(planScript.out.linesIterator.toSeq.contains("./Script.java:run"))
      assert(planScript.out.linesIterator.toSeq.contains("./Script.java:compile"))

      val resolveExternalTask = eval(("resolve", "mill.scalalib.scalafmt.ScalafmtModule:reformatAll"))
      assert(resolveExternalTask.out.linesIterator.toSeq == Seq("mill.scalalib.scalafmt.ScalafmtModule:reformatAll"))

      val planExternalTask = eval(("plan", "mill.scalalib.scalafmt.ScalafmtModule:scalafmtClasspath"))
      assert(
        planExternalTask.out.linesIterator.toSeq ==
        Seq(
          "mill.javalib.CoursierConfigModule:coursierEnv",
          "mill.scalalib.scalafmt.ScalafmtModule:repositories",
          "mill.scalalib.scalafmt.ScalafmtModule:checkGradleModules",
          "mill.scalalib.scalafmt.ScalafmtModule:scalafmtClasspath",
        )
      )

    }
  }
}
