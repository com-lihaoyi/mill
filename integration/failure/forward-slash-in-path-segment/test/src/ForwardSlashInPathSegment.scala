package mill.integration

import utest._

object ForwardSlashInPathSegmentTests extends IntegrationTestSuite {
  val tests = Tests {
    val workspaceRoot = initWorkspace()

    test("success") {
      val res = evalStdout("resolve", "foo._")
      assert(!res.isSuccess)
      assert(
        res.err.contains(
          "contains '/' which is not allowed. Try using os.SubPath"
        )
      )
    }
  }
}
