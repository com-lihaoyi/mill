package mill.integration

import mill.util.Util
import utest._

object ModuleInitErrorTests extends IntegrationTestSuite {
  def captureOutErr = true
  val tests = Tests {
    initWorkspace()

    test("rootTarget") {
      // If we specify a target in the root module, we are not
      // affected by the sub-modules failing to initialize
      val res = evalStdout("rootTarget")
      assert(res.isSuccess == true)
      assert(res.out.contains("""Running rootTarget"""))
    }
    test("rootCommand") {
      // If we specify a target in the root module, we are not
      // affected by the sub-modules failing to initialize
      val res = evalStdout("rootCommand", "hello")
      assert(res.isSuccess == true)
      assert(res.out.contains("""Running rootCommand hello"""))
    }
    test("fooTarget") {
      val res = evalStdout("foo.fooTarget")
      assert(res.isSuccess == false)
      assert(fansi.Str(res.err).plainText.contains("""java.lang.Exception: Foo Boom"""))
      // Make sure the stack trace is "short" and does not contain all the stack
      // frames from the Mill launcher
      assert(fansi.Str(res.err).plainText.linesIterator.size < 20)
    }
    test("fooCommand") {
      val res = evalStdout("foo.fooCommand", "hello")
      assert(res.isSuccess == false)
      assert(fansi.Str(res.err).plainText.contains("""java.lang.Exception: Foo Boom"""))
      assert(fansi.Str(res.err).plainText.linesIterator.size < 20)
    }
    test("barTarget") {
      val res = evalStdout("bar.barTarget")
      assert(res.isSuccess == true)
      assert(res.out.contains("""Running barTarget"""))
    }
    test("barCommand") {
      val res = evalStdout("bar.barCommand", "hello")
      assert(res.isSuccess == true)
      assert(res.out.contains("""Running barCommand hello"""))
    }
    test("quxTarget") {
      val res = evalStdout("bar.qux.quxTarget")
      assert(res.isSuccess == false)
      assert(fansi.Str(res.err).plainText.contains("""java.lang.Exception: Qux Boom"""))
      assert(fansi.Str(res.err).plainText.linesIterator.size < 20)
    }
    test("quxCommand") {
      val res = evalStdout("bar.qux.quxCommand", "hello")
      assert(res.isSuccess == false)
      assert(fansi.Str(res.err).plainText.contains("""java.lang.Exception: Qux Boom"""))
      assert(fansi.Str(res.err).plainText.linesIterator.size < 20)
    }
  }
}
