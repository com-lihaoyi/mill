package mill.integration

import utest._

class BetterFilesTests(fork: Boolean)
  extends IntegrationTestSuite("MILL_BETTERFILES_REPO", "better-files", fork) {
  val tests = Tests{
    initWorkspace()
    'test - {

      assert(eval("core.test"))
      assert(eval("akka.test"))
      assert(eval("benchmarks.test.compile"))

      val coreTestMeta = meta("core.test.test")
      assert(coreTestMeta.contains("better.files.FileSpec"))
      assert(coreTestMeta.contains("files should handle BOM"))

      for(scalaFile <- os.walk(workspacePath).filter(_.ext == "scala")){
        os.write.append(scalaFile, "\n}")
      }
      assert(!eval("akka.test"))
    }

  }
}
