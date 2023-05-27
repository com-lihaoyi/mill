package mill.integration

import utest._

import scala.collection.mutable

object ImportIvyWorkerInvalidation extends IntegrationTestSuite {

  val tests = Tests {
    test {
      val wsRoot = initWorkspace()
      assert(evalStdout("app.compile").isSuccess)
      mangleFile(wsRoot / "build.sc", _.replace("object app", "println(\"hello\"); object app"))
      assert(evalStdout("app.compile").isSuccess)
    }
  }
}
