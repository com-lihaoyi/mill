package mill.integration

import utest._

object WatchInputsTests extends IntegrationTestSuite {
  val tests = Tests {
    initWorkspace()
    test{
      val t = new Thread(() => {
        evalStdout("--watch", "bar")
      })

    }
  }
}
