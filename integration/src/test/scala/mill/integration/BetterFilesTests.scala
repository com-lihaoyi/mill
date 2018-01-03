package mill.integration

import ammonite.ops._
import utest._

object BetterFilesTests extends IntegrationTestSuite("MILL_BETTERFILES_REPO", "better-files") {
  val tests = Tests{
    initWorkspace()
    'test - {

      assert(eval("core.test"))
      assert(eval("akka.test"))

      for(scalaFile <- ls.rec(workspacePath).filter(_.ext == "scala")){
        write.append(scalaFile, "\n}")
      }
      assert(!eval("akka.test"))
    }

  }
}
