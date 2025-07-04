package mill.init.importer
package sbt

import utest.*
import utest.framework.TestPath

object SbtBuildExporterTests extends TestSuite {
  val resources = os.Path(sys.env("MILL_TEST_RESOURCE_DIR"))

  def setup[T](url: String, branch: String)(run: => T)(using tp: TestPath) =
    val sandbox = os.pwd / tp.value
    os.proc("git", "clone", "--depth", "1", url, "--branch", branch, sandbox)
      .call(stderr = os.Pipe)
    os.dynamicPwd.withValue(sandbox)(run)

  def diff(goldenFile: os.SubPath, exportFile: os.Path) =
    os.proc("git", "diff", "--no-index", resources / goldenFile, exportFile)
      .call(stdout = os.Inherit).exitCode

  def tests = Tests:
    test("airstream"):
      setup("https://github.com/raquo/Airstream", "v17.2.0"):
        val json = os.temp()
        SbtBuildExporter().exportBuild(json)
        //  .foreach(pprint.pprintln(_))
        assert(diff("airstream.export", json) == 0)

    test("fs2"):
      setup("https://github.com/typelevel/fs2", "v3.11.0"):
        val json = os.temp()
        SbtBuildExporter().exportBuild(json)
        //  .foreach(pprint.pprintln(_))
        assert(diff("fs2.export", json) == 0)
}
