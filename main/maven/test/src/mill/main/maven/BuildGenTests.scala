package mill.main.maven

import utest.*
import utest.framework.TestPath

object BuildGenTests extends TestSuite {

  def tests: Tests = Tests {
    val resources = os.Path(sys.env("MILL_TEST_RESOURCE_DIR"))

    // multi level nested modules
    test("maven-samples") {
      val expectedRoot = resources / "expected/maven-samples"

      val actualRoot = prep(resources / "maven-samples")
      val args = Array.empty[String]
      os.dynamicPwd.withValue(actualRoot)(BuildGen.main(args))

      checkBuild(actualRoot, expectedRoot) ==> true
    }

    test("config") {
      test("base-module") {
        val expectedRoot = resources / "expected/config/base-module"

        val actualRoot = prep(resources / "maven-samples/multi-module")
        val args = Array("--baseModule", "MyModule")
        os.dynamicPwd.withValue(actualRoot)(BuildGen.main(args))

        checkBuild(actualRoot, expectedRoot) ==> true
      }

      test("deps-object") {
        val actualRoot = prep(resources / "config/deps-object")
        val args = Array("--deps-object", "Deps")
        os.dynamicPwd.withValue(actualRoot)(BuildGen.main(args))

        val expectedRoot = resources / "expected/config/deps-object"
        checkBuild(actualRoot, expectedRoot) ==> true
      }

      test("no-share-publish") {
        val actualRoot = prep(resources / "maven-samples/multi-module")
        val args = Array("--no-share-publish")
        os.dynamicPwd.withValue(actualRoot)(BuildGen.main(args))

        val expectedRoot = resources / "expected/config/no-share-publish"
        checkBuild(actualRoot, expectedRoot) ==> true
      }

      test("publish-properties") {
        val actualRoot = prep(resources / "maven-samples/single-module")
        val args = Array("--publish-properties")
        os.dynamicPwd.withValue(actualRoot)(BuildGen.main(args))

        val expectedRoot = resources / "expected/config/publish-properties"
        checkBuild(actualRoot, expectedRoot) ==> true
      }
    }
  }

  def buildFiles(root: os.Path): Seq[os.Path] =
    os.walk.stream(root, skip = (root / "out").equals)
      .filter(_.ext == "mill").toSeq

  def checkBuild(actualRoot: os.Path, expectedRoot: os.Path): Boolean = {
    val actualFiles = buildFiles(actualRoot)
    val expectedFiles = buildFiles(expectedRoot)

    actualFiles.nonEmpty &&
    actualFiles.length == expectedFiles.length &&
    actualFiles.iterator.zip(expectedFiles.iterator).forall {
      case (actual, expected) =>
        actual.relativeTo(actualRoot) == expected.relativeTo(expectedRoot) &&
        os.read(actual) == os.read(expected)
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
