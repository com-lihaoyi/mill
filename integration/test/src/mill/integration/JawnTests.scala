package mill.integration

import utest._

class JawnTests(fork: Boolean)
  extends IntegrationTestSuite("MILL_JAWN_REPO", "jawn", fork) {
  val tests = Tests{
    initWorkspace()

    def check(scalaVersion: String) = {
      val firstCompile = eval(s"jawn[$scalaVersion].parser.test")

      assert(
        firstCompile,
        os.walk(workspacePath).exists(_.last == "AsyncParser.class"),
        os.walk(workspacePath).exists(_.last == "CharBuilderSpec.class")
      )

      for(scalaFile <- os.walk(workspacePath).filter(_.ext == "scala")){
        os.write.append(scalaFile, "\n}")
      }

      val brokenCompile = eval(s"jawn[$scalaVersion].parser.test")

      assert(!brokenCompile)
    }

    'scala21111 - check("2.11.11")
    'scala2123 - check("2.12.3")
  }
}
