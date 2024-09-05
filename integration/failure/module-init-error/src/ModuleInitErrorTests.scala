package mill.integration

import mill.testkit.IntegrationTestSuite

import utest._

object ModuleInitErrorTests extends IntegrationTestSuite {
  def captureOutErr = true
  val tests: Tests = Tests {
    test("resolve") - integrationTest { tester => import tester._
      // Ensure that resolve works even of the modules containing the resolved
      // tasks are broken
      val res1 = tester.eval(("resolve", "foo.fooTarget"))
      assert(res1.isSuccess == true)
      assert(res1.out.contains("foo.fooTarget"))

      val res2 = tester.eval(("resolve", "_._"))
      assert(res2.isSuccess == true)
      assert(
        res2.out.contains("bar.barCommand"),
        res2.out.contains("bar.barTarget"),
        res2.out.contains("bar.qux"),
        res2.out.contains("foo.fooTarget"),
        res2.out.contains("foo.fooCommand")
      )

      val res3 = tester.eval(("resolve", "__.fooTarget"))
      assert(res3.isSuccess == true)
      assert(res3.out.contains("foo.fooTarget"))

      val res4 = tester.eval(("resolve", "__"))
      assert(res4.isSuccess == true)
      assert(res4.out.contains("bar"))
      assert(res4.out.contains("bar.barCommand"))
      assert(res4.out.contains("bar.barTarget"))
      assert(res4.out.contains("bar.qux"))
      assert(res4.out.contains("bar.qux.quxCommand"))
      assert(res4.out.contains("bar.qux.quxTarget"))
      assert(res4.out.contains("foo"))
      assert(res4.out.contains("foo.fooCommand"))
      assert(res4.out.contains("foo.fooTarget"))
    }

    test("rootTarget") - integrationTest { tester => import tester._
      // If we specify a target in the root module, we are not
      // affected by the sub-modules failing to initialize
      val res = eval("rootTarget")
      assert(res.isSuccess == true)
      assert(res.out.contains("""Running rootTarget"""))
    }
    test("rootCommand") - integrationTest { tester => import tester._
      // If we specify a target in the root module, we are not
      // affected by the sub-modules failing to initialize
      val res = eval(("rootCommand", "-s", "hello"))
      assert(res.isSuccess == true)
      assert(res.out.contains("""Running rootCommand hello"""))
    }
    test("fooTarget") - integrationTest { tester => import tester._
      val res = eval("foo.fooTarget")
      assert(res.isSuccess == false)
      assert(fansi.Str(res.err).plainText.contains("""java.lang.Exception: Foo Boom"""))
      // Make sure the stack trace is "short" and does not contain all the stack
      // frames from the Mill launcher
      assert(fansi.Str(res.err).plainText.linesIterator.size < 20)
    }
    test("fooCommand") - integrationTest { tester => import tester._
      val res = eval(("foo.fooCommand", "-s", "hello"))
      assert(res.isSuccess == false)
      assert(fansi.Str(res.err).plainText.contains("""java.lang.Exception: Foo Boom"""))
      assert(fansi.Str(res.err).plainText.linesIterator.size < 20)
    }
    test("barTarget") - integrationTest { tester => import tester._
      val res = eval("bar.barTarget")
      assert(res.isSuccess == true)
      assert(res.out.contains("""Running barTarget"""))
    }
    test("barCommand") - integrationTest { tester => import tester._
      val res = eval(("bar.barCommand", "-s", "hello"))
      assert(res.isSuccess == true)
      assert(res.out.contains("""Running barCommand hello"""))
    }
    test("quxTarget") - integrationTest { tester => import tester._
      val res = eval("bar.qux.quxTarget")
      assert(res.isSuccess == false)
      assert(fansi.Str(res.err).plainText.contains("""java.lang.Exception: Qux Boom"""))
      assert(fansi.Str(res.err).plainText.linesIterator.size < 20)
    }
    test("quxCommand") - integrationTest { tester => import tester._
      val res = eval(("bar.qux.quxCommand", "-s", "hello"))
      assert(res.isSuccess == false)
      assert(fansi.Str(res.err).plainText.contains("""java.lang.Exception: Qux Boom"""))
      assert(fansi.Str(res.err).plainText.linesIterator.size < 20)
    }
  }
}
