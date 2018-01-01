package mill.integration

import ammonite.ops._
import utest._

object BetterFilesTests extends IntegrationTestSuite("MILL_BETTERFILES_REPO", "better-files") {
  val tests = Tests{
    initWorkspace()
    'test - {

      assert(eval("Core.test"))
      assert(eval("Akka.test"))

      for(scalaFile <- ls.rec(workspacePath).filter(_.ext == "scala")){
        write.append(scalaFile, "\n}")
      }
      assert(!eval("Akka.test"))
    }

  }
}
