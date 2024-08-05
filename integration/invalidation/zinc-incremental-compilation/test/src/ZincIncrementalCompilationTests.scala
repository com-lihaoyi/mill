package mill.integration

import utest._

// Regress test for issue https://github.com/com-lihaoyi/mill/issues/1901
object ZincIncrementalCompilationTests extends IntegrationTestSuite {
  val tests: Tests = Tests {
    initWorkspace()
    "incremental compilation only compiles changed files" - {
      val successful = eval("app.compile")
      assert(successful)

      val appSrc = wd / "app" / "src" / "main" / "scala" / "App.scala"
      val classes = wd / "out" / "app" / "compile.dest" / "classes"
      val app = classes / "app" / "App.class"
      val model = classes / "models" / "Foo.class"
      assert(Seq(classes, app, model, appSrc).forall(os.exists))

      val appSrcInfo1 = os.stat(appSrc)
      val appInfo1 = os.stat(app)
      val modelInfo1 = os.stat(model)

      println("** second run **")
      os.write.append(appSrc, "\n ")

      val succ2nd = eval("app.compile")
      assert(succ2nd)

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
