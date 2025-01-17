package mill.main.buildgen

import mill.main.client.{CodeGenConstants, OutFiles}
import mill.scalalib.scalafmt.ScalafmtModule
import mill.testkit.{TestBaseModule, UnitTester}
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
    val actualRoot = os.pwd / tp.value
    os.copy.over(sourceRoot / sourceRel, actualRoot, createFolders = true, replaceExisting = true)

    // gen
    os.dynamicPwd.withValue(actualRoot)(generate)

    // fmt
    val files = BuildGenUtil.buildFiles(actualRoot).map(PathRef(_)).toSeq
    object module extends TestBaseModule with ScalafmtModule {
      override def filesToFormat(sources: Seq[PathRef]): Seq[PathRef] = files
      override def scalafmtConfig: T[Seq[PathRef]] = Seq(PathRef(scalafmtConfigFile))
    }
    val eval = UnitTester(module, actualRoot)
    eval(module.reformat())

    // check
    val expectedRoot = sourceRoot / expectedRel
    // Non *.mill files, that are not in test data, that we don't want
    // to see in the diff
    val toCleanUp = os.walk(actualRoot, skip = (actualRoot / OutFiles.out).equals)
      .filter(os.isFile)
      .filterNot(file => CodeGenConstants.buildFileExtensions.contains(file.ext))
    toCleanUp.foreach(os.remove)

    // Try to normalize permissions while not touching those of committed test data
    val supportsPerms = FileSystems.getDefault.supportedFileAttributeViews().contains("posix")
    if (supportsPerms)
      for {
        testFile <- os.walk(expectedRoot)
        if os.isFile(testFile)
        targetFile = actualRoot / testFile.relativeTo(expectedRoot).asSubPath
        if os.isFile(targetFile)
      }
        os.perms.set(targetFile, os.perms(testFile))

    val diffExitCode = os.proc("git", "diff", "--no-index", expectedRoot, actualRoot)
      .call(stdin = os.Inherit, stdout = os.Inherit, check = !updateSnapshots)
      .exitCode

    if (updateSnapshots && diffExitCode != 0) {
      System.err.println(
        s"Expected and actual files differ, update expected files in resources under $expectedRoot"
      )

      os.remove.all(expectedRoot)
      os.copy(actualRoot, expectedRoot)
    }

    diffExitCode == 0 || updateSnapshots
  }
}
object BuildGenChecker {

  def apply(sourceRoot: os.Path = os.Path(sys.env("MILL_TEST_RESOURCE_DIR"))): BuildGenChecker =
    new BuildGenChecker(sourceRoot, BuildGenUtil.scalafmtConfigFile)
}
