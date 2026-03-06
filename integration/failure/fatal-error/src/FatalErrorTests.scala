package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest.*

object FatalErrorTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test - integrationTest { tester =>
      val res = tester.eval("fatalTask")

      assert(res.isSuccess == false)
      assert(res.err.contains("""java.lang.InterruptedException: CUSTOM FATAL ERROR IN TASK"""))

      // Only run this test in client-server mode, since workers are not shutdown
      // with `close()` in no-server mode so the error does not trigger
      if (daemonMode) {
        // This worker invalidates re-evaluates every time due to being dependent on
        // an upstream `Task.Input`. Make sure that a fatal error in the `close()`
        // call does not hang the Mill process
        tester.eval("fatalCloseWorker")
        val res3 = tester.eval("fatalCloseWorker")
        assert(res3.err.contains("""java.lang.InterruptedException: CUSTOM FATAL ERROR"""))
      }

      val res1 = tester.eval("fatalTaskSerializedEx")
      // no SerializedException in stack trace, only its nested serialized one
      assert(!res1.err.contains("Result$SerializedException"))
      assert(res1.err.contains("foo.ThingException: This is a thing exception"))
      // check that the first and last stack trace lines are in the output
      assert(res1.err.contains("Foo.thing(Foos.scala:45)"))
      assert(res1.err.contains("Foo$.thing$$anon$486(Foos.scala:63)"))

      val res2 = tester.eval("fatalTaskNoSharedStackTrace")
      // no anonymous class made-up name
      assert(!res2.err.contains("build_.package_"))
      assert(res2.err.contains("foo.ThingException: This is a thing exception"))
      // check that the first and last stack trace lines are in the output
      assert(res2.err.contains("Foo.thing(Foos.scala:45)"))
      assert(res2.err.contains("Foo$.thing$$anon$486(Foos.scala:63)"))
    }
  }
}
