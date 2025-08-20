package mill.main.buildgen

import mill.api.Discover
import mill.scalalib.scalafmt.ScalafmtModule
import mill.testkit.{TestRootModule, UnitTester}
import mill.util.TokenReaders.*
import mill.{PathRef, T}
import utest.framework.TestPath

import java.nio.file.FileSystems

class BuildGenChecker(sourceRoot: os.Path, scalafmtConfigFile: os.Path) {

  def check(
      generate: => Unit,
      sourceRel: os.SubPath,
      expectedRel: os.SubPath,
      updateSnapshots: Boolean = false // pass true to update test data on disk
  )(implicit
      tp: TestPath
  ): Boolean = {
    // prep
    val testRoot = os.pwd / tp.value
    os.copy.over(sourceRoot / sourceRel, testRoot, createFolders = true, replaceExisting = true)

    // gen
    os.dynamicPwd.withValue(testRoot)(generate)

    // fmt
    val buildFiles = mill.init.Util.buildFiles(testRoot)
    object module extends TestRootModule with ScalafmtModule {
      override def filesToFormat(sources: Seq[PathRef]): Seq[PathRef] = buildFiles.map(PathRef(_))

      override def scalafmtConfig: T[Seq[PathRef]] = Seq(PathRef(scalafmtConfigFile))

      lazy val millDiscover = Discover[this.type]
    }
    UnitTester(module, testRoot).scoped { eval =>
      eval(module.reformat())

      // Remove non-build files before computing diff
      val toCleanUp = os.walk.stream(testRoot, skip = buildFiles.contains)
        .filter(os.isFile)
        .toSeq
      toCleanUp.foreach(os.remove)

      val expectedRoot = sourceRoot / expectedRel
      // Try to normalize permissions while not touching those of committed test data
      val supportsPerms = FileSystems.getDefault.supportedFileAttributeViews().contains("posix")
      if (supportsPerms)
        for {
          testFile <- os.walk(expectedRoot)
          if os.isFile(testFile)
          targetFile = testRoot / testFile.relativeTo(expectedRoot).asSubPath
          if os.isFile(targetFile)
        }
          os.perms.set(targetFile, os.perms(testFile))

      val diffExitCode = os.proc("git", "diff", "--no-index", expectedRoot, testRoot)
        .call(stdin = os.Inherit, stdout = os.Inherit, check = !updateSnapshots)
        .exitCode

      if (updateSnapshots && diffExitCode != 0) {
        System.err.println(
          s"Expected and actual files differ, update expected files in resources under $expectedRoot"
        )

        os.remove.all(expectedRoot)
        os.copy(testRoot, expectedRoot)
      }

      diffExitCode == 0 || updateSnapshots
    }
  }
}
object BuildGenChecker {

  def apply(sourceRoot: os.Path = os.Path(sys.env("MILL_TEST_RESOURCE_DIR"))): BuildGenChecker =
    new BuildGenChecker(sourceRoot, mill.init.Util.scalafmtConfigFile)
}
