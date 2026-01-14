package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest.*

// Regress test for issue https://github.com/com-lihaoyi/mill/issues/1901
object ZincIncrementalCompilationTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test("incremental compilation only compiles changed files") - integrationTest { tester =>
      import tester.*
      val successful = tester.eval("app.compile")
      assert(successful.isSuccess)

      val appSrc = workspacePath / "app/src/main/scala/App.scala"
      val classes = workspacePath / "out/app/compile.dest/classes"
      val app = classes / "app/App.class"
      val model = classes / "models/Foo.class"
      assert(Seq(classes, app, model, appSrc).forall(os.exists))

      val appSrcInfo1 = os.stat(appSrc)
      val appInfo1 = os.stat(app)
      val modelInfo1 = os.stat(model)

      println("** second run **")
      os.write.append(appSrc, "\n ")

      val succ2nd = tester.eval("app.compile")
      assert(succ2nd.isSuccess)

      val appSrcInfo2 = os.stat(appSrc)
      val appInfo2 = os.stat(app)
      val modelInfo2 = os.stat(model)

      // we changed it
      assert(appSrcInfo1.mtime != appSrcInfo2.mtime)
      // expected to be re-compiled
      assert(appInfo1.ctime != appInfo2.ctime)
      // expected to be NOT re-compiled
      assert(modelInfo1.ctime == modelInfo2.ctime)
    }
  }
}
