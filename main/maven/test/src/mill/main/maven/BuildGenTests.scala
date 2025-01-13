package mill.main.maven

import mill.T
import mill.api.PathRef
import mill.main.client.OutFiles
import mill.scalalib.scalafmt.ScalafmtModule
import mill.testkit.{TestBaseModule, UnitTester}
import utest.*
import utest.framework.TestPath

import java.nio.file.FileSystems

object BuildGenTests extends TestSuite {

  // Change this to true to update test data on disk
  def updateSnapshots = false

  def tests: Tests = Tests {
    val resources = os.Path(sys.env("MILL_TEST_RESOURCE_DIR"))
    val scalafmtConfigFile = PathRef(resources / ".scalafmt.conf")

    def checkBuild(sourceRel: os.SubPath, expectedRel: os.SubPath, args: String*)(implicit
        tp: TestPath
    ): Boolean = {
      // prep
      val dest = os.pwd / tp.value
      os.copy.over(resources / sourceRel, dest, createFolders = true, replaceExisting = true)

      // gen
      os.dynamicPwd.withValue(dest)(BuildGen.main(args.toArray))

      // fmt
      val files = buildFiles(dest)
      object module extends TestBaseModule with ScalafmtModule {
        override protected def filesToFormat(sources: Seq[PathRef]): Seq[PathRef] = files
        override def scalafmtConfig: T[Seq[PathRef]] = Seq(scalafmtConfigFile)
      }
      val eval = UnitTester(module, dest)
      eval(module.reformat())

      // test
      checkFiles(files.map(_.path.relativeTo(dest).asSubPath), dest, resources / expectedRel)
    }

    // multi level nested modules
    test("maven-samples") {
      val sourceRoot = os.sub / "maven-samples"
      val expectedRoot = os.sub / "expected/maven-samples"
      assert(
        checkBuild(sourceRoot, expectedRoot)
      )
    }

    test("config") {
      test("all") {
        val sourceRoot = os.sub / "maven-samples"
        val expectedRoot = os.sub / "expected/config/all"
        assert(
          checkBuild(
            sourceRoot,
            expectedRoot,
            "--base-module",
            "MyModule",
            "--test-module",
            "tests",
            "--deps-object",
            "Deps",
            "--publish-properties",
            "--merge",
            "--cache-repository",
            "--process-plugins"
          )
        )
      }

      test("base-module") {
        val sourceRoot = os.sub / "maven-samples/multi-module"
        val expectedRoot = os.sub / "expected/config/base-module"
        assert(
          checkBuild(sourceRoot, expectedRoot, "--base-module", "MyModule")
        )
      }

      test("deps-object") {
        val sourceRoot = os.sub / "config/deps-object"
        val expectedRoot = os.sub / "expected/config/deps-object"
        assert(
          checkBuild(sourceRoot, expectedRoot, "--deps-object", "Deps")
        )
      }

      test("test-module") {
        val sourceRoot = os.sub / "maven-samples/single-module"
        val expectedRoot = os.sub / "expected/config/test-module"
        assert(
          checkBuild(sourceRoot, expectedRoot, "--test-module", "tests")
        )
      }

      test("merge") {
        val sourceRoot = os.sub / "maven-samples"
        val expectedRoot = os.sub / "expected/config/merge"
        assert(
          checkBuild(sourceRoot, expectedRoot, "--merge")
        )
      }

      test("publish-properties") {
        val sourceRoot = os.sub / "maven-samples/single-module"
        val expectedRoot = os.sub / "expected/config/publish-properties"
        assert(
          checkBuild(sourceRoot, expectedRoot, "--publish-properties")
        )
      }
    }

    test("misc") {
      test("custom-resources") {
        val sourceRoot = os.sub / "misc/custom-resources"
        val expectedRoot = os.sub / "expected/misc/custom-resources"
        assert(
          checkBuild(sourceRoot, expectedRoot)
        )
      }
    }
  }

  def buildFiles(root: os.Path): Seq[PathRef] =
    os.walk.stream(root, skip = (root / "out").equals)
      .filter(_.ext == "mill")
      .map(PathRef(_))
      .toSeq

  def checkFiles(actualFiles: Seq[os.SubPath], root: os.Path, expectedRoot: os.Path): Boolean = {
    // Non *.mill files, that are not in test data, that we don't want
    // to see in the diff
    val toCleanUp = os.walk(root, skip = _.startsWith(root / OutFiles.defaultOut))
      .filter(os.isFile)
      .filter(!_.lastOpt.exists(_.endsWith(".mill")))
    toCleanUp.foreach(os.remove)

    // Try to normalize permissions while not touching those of committed test data
    val supportsPerms = FileSystems.getDefault.supportedFileAttributeViews().contains("posix")
    if (supportsPerms)
      for {
        testFile <- os.walk(expectedRoot)
        if os.isFile(testFile)
        targetFile = root / testFile.relativeTo(expectedRoot).asSubPath
        if os.isFile(targetFile)
      }
        os.perms.set(targetFile, os.perms(testFile))

    val diffExitCode = os.proc("git", "diff", "--no-index", expectedRoot, root)
      .call(stdin = os.Inherit, stdout = os.Inherit, check = !updateSnapshots)
      .exitCode

    if (updateSnapshots && diffExitCode != 0) {
      System.err.println(
        s"Expected and actual files differ, updating expected files in resources under $expectedRoot"
      )

      os.remove.all(expectedRoot)
      os.copy(root, expectedRoot)
    }

    diffExitCode == 0 || updateSnapshots
  }
}
