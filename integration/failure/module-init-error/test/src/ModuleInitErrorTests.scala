package mill.integration

import mill.util.Util
import utest._

object ModuleInitErrorTests extends IntegrationTestSuite {
  def captureOutErr = true
  val tests = Tests {
    initWorkspace()

    test("root") {
      // If we specify a target in the root module, we are not
      // affected by the sub-modules failing to initialize
      val res = evalStdout("rootTarget")

      assert(res.isSuccess == true)
      assert(res.out.contains("""rooty"""))

      val res2 = evalStdout("show", "rootTarget")

      assert(res2.isSuccess == true)
      assert(res2.out.contains(""""rooty2""""))
    }
    test("foo") {
      println("foo 1")
      val res = evalStdout("foo.fooTarget")
      println("foo 2")

      assert(res.isSuccess == false)
      println("foo 3")
      assert(fansi.Str(res.err).plainText.contains("""java.lang.Exception: Foo Boom"""))
      println("foo 4")
    }
    test("barQux") {
      val res = evalStdout("bar.qux.quxTarget")

      assert(res.isSuccess == false)
      assert(fansi.Str(res.err).plainText.contains("""java.lang.Exception: Qux Boom"""))
    }
  }
}
