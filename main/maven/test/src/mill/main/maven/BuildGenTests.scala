package mill.main.maven

import mill.api.PathRef
import mill.scalalib.scalafmt.ScalafmtModule
import mill.testkit.{TestBaseModule, UnitTester}
import utest.*
import utest.framework.TestPath

object BuildGenTests extends TestSuite {

  def tests: Tests = Tests {
    val resources = os.Path(sys.env("MILL_TEST_RESOURCE_DIR"))
    val scalafmtConfig = PathRef(resources / ".scalafmt.conf")

    // multi level nested modules
    test("maven-samples") {
      val actualRoot = prep(resources / "maven-samples")
      val args = Array.empty[String]
      os.dynamicPwd.withValue(actualRoot)(BuildGen.main(args))

      val expectedRoot = resources / "expected/maven-samples"
      assert(
        checkBuild(buildFiles(actualRoot, scalafmtConfig), expectedRoot)
      )
    }

    test("config") {
      test("base-module") {
        val actualRoot = prep(resources / "maven-samples/multi-module")
        val args = Array("--baseModule", "MyModule")
        os.dynamicPwd.withValue(actualRoot)(BuildGen.main(args))

        val expectedRoot = resources / "expected/config/base-module"
        assert(
          checkBuild(buildFiles(actualRoot, scalafmtConfig), expectedRoot)
        )
      }

      test("deps-object") {
        val actualRoot = prep(resources / "config/deps-object")
        val args = Array("--deps-object", "Deps")
        os.dynamicPwd.withValue(actualRoot)(BuildGen.main(args))

        val expectedRoot = resources / "expected/config/deps-object"
        assert(
          checkBuild(buildFiles(actualRoot, scalafmtConfig), expectedRoot)
        )
      }

      test("no-share-publish") {
        val actualRoot = prep(resources / "maven-samples/multi-module")
        val args = Array("--no-share-publish")
        os.dynamicPwd.withValue(actualRoot)(BuildGen.main(args))

        val expectedRoot = resources / "expected/config/no-share-publish"
        assert(
          checkBuild(buildFiles(actualRoot, scalafmtConfig), expectedRoot)
        )
      }

      test("publish-properties") {
        val actualRoot = prep(resources / "maven-samples/single-module")
        val args = Array("--publish-properties")
        os.dynamicPwd.withValue(actualRoot)(BuildGen.main(args))

        val expectedRoot = resources / "expected/config/publish-properties"
        assert(
          checkBuild(buildFiles(actualRoot, scalafmtConfig), expectedRoot)
        )
      }
    }
  }

  def buildFiles(root: os.Path): Seq[PathRef] =
    os.walk.stream(root, skip = (root / "out").equals)
      .filter(_.ext == "mill")
      .map(PathRef(_))
      .toSeq

  def buildFiles(root: os.Path, scalafmtConfigFile: PathRef): Seq[PathRef] = {
    val files = buildFiles(root)
    object module extends TestBaseModule with ScalafmtModule {
      override protected def filesToFormat(sources: Seq[PathRef]) = files
      override def scalafmtConfig = Seq(scalafmtConfigFile)
    }
    val eval = UnitTester(module, root)
    eval(module.reformat())
    files
  }

  def checkBuild(actualFiles: Seq[PathRef], expectedRoot: os.Path): Boolean = {
    val expectedFiles = buildFiles(expectedRoot)

    actualFiles.nonEmpty &&
    actualFiles.length == expectedFiles.length &&
    actualFiles.iterator.zip(expectedFiles.iterator).forall {
      case (actual, expected) =>
        actual.path.endsWith(expected.path.relativeTo(expectedRoot)) &&
        os.read(actual.path) == os.read(expected.path)
    }
  }

  def prep(sourceRoot: os.Path)(implicit tp: TestPath): os.Path = {
    val segments = tp.value
    val directory = os.pwd / segments
    if (os.exists(directory)) os.remove.all(directory)
    os.copy(sourceRoot, directory, createFolders = true)
    directory
  }
}
