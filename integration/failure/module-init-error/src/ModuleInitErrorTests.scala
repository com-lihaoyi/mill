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

    test("rootTask") - integrationTest { tester =>
      import tester._
      // If we specify a task in the root module, we are not
      // affected by the sub-modules failing to initialize
      val res = eval("rootTask")
      assert(res.isSuccess == true)
      assert(res.out.contains("""Running rootTask"""))
    }
    test("rootCommand") - integrationTest { tester =>
      import tester._
      // If we specify a task in the root module, we are not
      // affected by the sub-modules failing to initialize
      val res = eval(("rootCommand", "-s", "hello"))
      assert(res.isSuccess == true)
      assert(res.out.contains("""Running rootCommand hello"""))
    }
    test("fooTask") - integrationTest { tester =>
      import tester._
      val res = eval("foo.fooTask")
      assert(res.isSuccess == false)
      assert(fansi.Str(res.err).plainText.contains("""java.lang.Exception: Foo Boom"""))
      // Make sure the stack trace is "short" and does not contain all the stack
      // frames from the Mill launcher
      assert(fansi.Str(res.err).plainText.linesIterator.size < 20)
    }
    test("fooCommand") - integrationTest { tester =>
      import tester._
      val res = eval(("foo.fooCommand", "-s", "hello"))
      assert(res.isSuccess == false)
      assert(fansi.Str(res.err).plainText.contains("""java.lang.Exception: Foo Boom"""))
      assert(fansi.Str(res.err).plainText.linesIterator.size < 20)
    }
    test("barTask") - integrationTest { tester =>
      import tester._
      val res = eval("bar.barTask")
      assert(res.isSuccess == true)
      assert(res.out.contains("""Running barTask"""))
    }
    test("barCommand") - integrationTest { tester =>
      import tester._
      val res = eval(("bar.barCommand", "-s", "hello"))
      assert(res.isSuccess == true)
      assert(res.out.contains("""Running barCommand hello"""))
    }
    test("quxTask") - integrationTest { tester =>
      import tester._
      val res = eval("bar.qux.quxTask")
      assert(res.isSuccess == false)
      assert(fansi.Str(res.err).plainText.contains("""java.lang.Exception: Qux Boom"""))
      assert(fansi.Str(res.err).plainText.linesIterator.size < 20)
    }
    test("quxCommand") - integrationTest { tester =>
      import tester._
      val res = eval(("bar.qux.quxCommand", "-s", "hello"))
      assert(res.isSuccess == false)
      assert(fansi.Str(res.err).plainText.contains("""java.lang.Exception: Qux Boom"""))
      assert(fansi.Str(res.err).plainText.linesIterator.size < 20)
    }
  }
}
