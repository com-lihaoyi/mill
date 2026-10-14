package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest.*

// Ensure, we don't leak absolute paths in generated/compiled files (e.g. TASTy)
// see https://github.com/com-lihaoyi/mill/issues/7754
object ScalacNotLeakingAbsolutePathsTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test("compile") - integrationTest { tester =>
      import tester.*

      val successful = tester.eval("compile")
      assert(successful.isSuccess)

      checkClassesDir(workspacePath, workspacePath / "out/compile.dest/classes")

    }
  }

  def checkClassesDir(baseDir: os.Path, classesDir: os.Path): Unit = {
    val projRootStr = baseDir.toString()
    val candidatess = os.walk(classesDir).filter(os.isFile).filter(os.read(_).contains(projRootStr))
    assert(candidatess == Seq())
  }
}
