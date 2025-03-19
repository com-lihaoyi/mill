package mill.integration

import mill.testkit.{IntegrationTester, UtestIntegrationTestSuite}

abstract class BuildGenTestSuite extends UtestIntegrationTestSuite {

  def initMessage(buildFileCount: Int): String =
    s"generated $buildFileCount Mill build file(s)"

  def integrationTest[T](githubSrcZipUrl: String)(f: IntegrationTester => T): T =
    super.integrationTest { tester =>
      println(s"downloading $githubSrcZipUrl")
      val zipFile = os.temp(requests.get(githubSrcZipUrl))
      val unzipDir = os.unzip(zipFile, os.temp.dir())
      val sourceDir = os.list(unzipDir).head
      // move fails on Windows, so copy
      for (p <- os.list(sourceDir)) {
        os.copy.into(p, tester.workspacePath, replaceExisting = true, createFolders = true)
      }
      f(tester)
    }
}
