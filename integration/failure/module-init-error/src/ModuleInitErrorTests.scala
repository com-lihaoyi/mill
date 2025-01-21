package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest._

object ModuleInitErrorTests extends UtestIntegrationTestSuite {
  def captureOutErr = true
  val tests: Tests = Tests {
    test("resolve") - integrationTest { tester =>
      // Ensure that resolve works even of the modules containing the resolved
      // tasks are broken
      val res1 = tester.eval(("resolve", "foo.fooTask"))
      assert(res1.isSuccess == true)
      assert(res1.out.contains("foo.fooTask"))

      val res2 = tester.eval(("resolve", "_._"))
      assert(res2.isSuccess == true)
      assert(
        res2.out.contains("bar.barCommand"),
        res2.out.contains("bar.barTask"),
        res2.out.contains("bar.qux"),
        res2.out.contains("foo.fooTask"),
        res2.out.contains("foo.fooCommand")
      )

      val res3 = tester.eval(("resolve", "__.fooTask"))
      assert(res3.isSuccess == true)
      assert(res3.out.contains("foo.fooTask"))

      val res4 = tester.eval(("resolve", "__"))
      assert(res4.isSuccess == true)
      assert(res4.out.contains("bar"))
      assert(res4.out.contains("bar.barCommand"))
      assert(res4.out.contains("bar.barTask"))
      assert(res4.out.contains("bar.qux"))
      assert(res4.out.contains("bar.qux.quxCommand"))
      assert(res4.out.contains("bar.qux.quxTask"))
      assert(res4.out.contains("foo"))
      assert(res4.out.contains("foo.fooCommand"))
      assert(res4.out.contains("foo.fooTask"))
    }

    test("tasks") - integrationTest { tester =>
      // If we specify a task in the root module, we are not
      // affected by the sub-modules failing to initialize
      import tester._
      val res = eval("rootTask")
      assert(res.isSuccess == true)
      assert(res.out.contains("""Running rootTask"""))

      // If we specify a task in the root module, we are not
      // affected by the sub-modules failing to initialize
      val res1 = eval(("rootCommand", "-s", "hello"))
      assert(res1.isSuccess == true)
      assert(res1.out.contains("""Running rootCommand hello"""))

      val res2 = eval("foo.fooTask")
      assert(res2.isSuccess == false)
      assert(fansi.Str(res2.err).plainText.contains("""java.lang.Exception: Foo Boom"""))
      // Make sure the stack trace is "short" and does not contain all the stack
      // frames from the Mill launcher
      assert(fansi.Str(res2.err).plainText.linesIterator.size < 20)

      val res3 = eval(("foo.fooCommand", "-s", "hello"))
      assert(res3.isSuccess == false)
      assert(fansi.Str(res3.err).plainText.contains("""java.lang.Exception: Foo Boom"""))
      assert(fansi.Str(res3.err).plainText.linesIterator.size < 20)

      val res4 = eval("bar.barTask")
      assert(res4.isSuccess == true)
      assert(res4.out.contains("""Running barTask"""))

      val res5 = eval(("bar.barCommand", "-s", "hello"))
      assert(res5.isSuccess == true)
      assert(res5.out.contains("""Running barCommand hello"""))

      val res6 = eval("bar.qux.quxTask")
      assert(res6.isSuccess == false)
      assert(fansi.Str(res6.err).plainText.contains("""java.lang.Exception: Qux Boom"""))
      assert(fansi.Str(res6.err).plainText.linesIterator.size < 20)

      val res7 = eval(("bar.qux.quxCommand", "-s", "hello"))
      assert(res7.isSuccess == false)
      assert(fansi.Str(res7.err).plainText.contains("""java.lang.Exception: Qux Boom"""))
      assert(fansi.Str(res7.err).plainText.linesIterator.size < 20)
    }
  }
}
