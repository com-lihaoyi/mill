package mill.integration

import ammonite.ops._
import utest._

object BetterFilesTests extends IntegrationTestSuite("MILL_BETTERFILES_REPO", "better-files") {
  val tests = Tests{
    initWorkspace()
    'test - {

      assert(eval("core.test.compile"))
      assert(eval("akka.test"))
      assert(eval("benchmarks.test.compile"))

      for(scalaFile <- ls.rec(workspacePath).filter(_.ext == "scala")){
        write.append(scalaFile, "\n}")
      }
      assert(!eval("akka.test"))
    }

  }
}
