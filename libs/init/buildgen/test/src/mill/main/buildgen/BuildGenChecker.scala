package mill.main.buildgen

import mill.api.Discover
import mill.init.Util
import mill.scalalib.scalafmt.ScalafmtModule
import mill.testkit.{TestRootModule, UnitTester}
import mill.util.Jvm
import mill.util.TokenReaders.*
import mill.{PathRef, T}
import utest.framework.TestPath

import java.nio.file.FileSystems

class BuildGenChecker(mainAssembly: os.Path, sourceRoot: os.Path, scalafmtConfigFile: os.Path) {

  def check(
      sourceRel: os.SubPath,
      expectedRel: os.SubPath,
      initArgs: Seq[String] = Nil,
      updateSnapshots: Boolean = false // pass true to update test data on disk
  )(using tp: TestPath): Boolean = {
    val testRoot = os.pwd / tp.value
    os.copy.over(sourceRoot / sourceRel, testRoot, createFolders = true, replaceExisting = true)

    os.proc(Jvm.javaExe(None), "-jar", mainAssembly, initArgs)
      .call(cwd = testRoot, stdout = os.Inherit)

    val buildFiles = Util.buildFiles(testRoot)
    // Only format Scala files, not YAML files
    val scalaFiles = buildFiles.filter(_.last.endsWith(".mill"))
    if (scalaFiles.nonEmpty) {
      object module extends TestRootModule with ScalafmtModule {
        override def filesToFormat(sources: Seq[PathRef]): Seq[PathRef] = scalaFiles.map(PathRef(_))

        override def scalafmtConfig: T[Seq[PathRef]] = Seq(PathRef(scalafmtConfigFile))

        lazy val millDiscover = Discover[this.type]
      }
      UnitTester(module, testRoot).scoped { eval =>
        eval(module.reformat())
      }
    }
    locally {

      // Remove non-build files before computing diff
      val toCleanUp = os.walk.stream(testRoot, skip = buildFiles.contains)
        .filter(os.isFile)
        .toSeq
      toCleanUp.foreach(os.remove)

      val expectedRoot = sourceRoot / expectedRel
      // Try to normalize permissions while not touching those of committed test data
      val supportsPerms = FileSystems.getDefault.supportedFileAttributeViews().contains("posix")
      if (supportsPerms && os.exists(expectedRoot))
        for {
          testFile <- os.walk(expectedRoot)
          if os.isFile(testFile)
          targetFile = testRoot / testFile.relativeTo(expectedRoot).asSubPath
          if os.isFile(targetFile)
        }
          os.perms.set(targetFile, os.perms(testFile))

      val diffExitCode =
        if (!os.exists(expectedRoot)) 1  // Force update if expected doesn't exist
        else os.proc("git", "diff", "--no-index", expectedRoot, testRoot)
          .call(stdin = os.Inherit, stdout = os.Inherit, check = !updateSnapshots)
          .exitCode

      if (updateSnapshots && diffExitCode != 0) {
        System.err.println(
          s"Expected and actual files differ, update expected files in resources under $expectedRoot"
        )

        os.remove.all(expectedRoot)
        os.copy(testRoot, expectedRoot, createFolders = true)
      }

      diffExitCode == 0 || updateSnapshots
    }
  }
}
object BuildGenChecker {

  def apply(
      mainAssembly: os.Path = os.Path(sys.env("TEST_MAIN_ASSEMBLY")),
      sourceRoot: os.Path = os.Path(sys.env("MILL_TEST_RESOURCE_DIR"))
  ): BuildGenChecker = new BuildGenChecker(
    mainAssembly = mainAssembly,
    sourceRoot = sourceRoot,
    scalafmtConfigFile = os.temp(Util.scalafmtConfig)
  )
}
