package mill.integration

import utest._

import scala.collection.mutable

object ImportIvyWorkerInvalidation extends IntegrationTestSuite {

  val tests = Tests {
    test {
      val wsRoot = initWorkspace()
      assert(eval("app.compile"))
      mangleFile(wsRoot / "build.sc", _.replace("object app", "println(\"hello\"); object app"))
      assert(eval("app.compile"))
    }
  }
}
