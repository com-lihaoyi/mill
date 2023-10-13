package mill.integration

import utest._

object DestDirectoryCollisionTests extends IntegrationTestSuite {
  val tests = Tests {
    val workspaceRoot = initWorkspace()

    test("success") {
      val res = evalStdout("resolve", "foo._")
      assert(!res.isSuccess)
      assert(
        res.err.contains(
          "have colliding destination path segments"
        )
      )
    }
  }
}
