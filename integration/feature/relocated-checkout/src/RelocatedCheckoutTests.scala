package mill.integration

import mill.testkit.{IntegrationTester, UtestIntegrationTestSuite}
import utest.*

/**
 * Checks that a built checkout keeps working after it is moved to another folder, together
 * with its `out/` folder.
 */
object RelocatedCheckoutTests extends UtestIntegrationTestSuite {
  // The two checkouts must not share an `out/` folder
  override def allowSharedOutputDir: Boolean = false

  // Moves the checkout at `from` into `tester`'s workspace, like `mv`: files are copied with
  // their timestamps, then the original build and sources are deleted. The daemon and lock
  // files are skipped, since the first run's daemon may still hold them.
  def moveCheckout(from: os.Path, tester: IntegrationTester): Unit = {
    val skip = Set("mill-daemon", "mill-no-daemon", "mill-out-lock")
    os.remove.all(tester.workspacePath)
    for (p <- os.walk(from, skip = p => skip(p.last)) if !os.isDir(p))
      os.copy(
        p,
        tester.workspacePath / p.relativeTo(from),
        copyAttributes = true,
        createFolders = true
      )
    for (p <- os.list(from) if p.last != "out") os.remove.all(p)
  }

  // Builds a checkout, moves it, edits a source in the new folder and checks that the edited
  // code runs
  def checkMove(
      setup: os.Path => Unit = _ => (),
      afterMove: IntegrationTester => Unit = _ => ()
  ): Unit = {
    val first = integrationTest { tester =>
      setup(tester.workspacePath)
      assert(tester.eval("run").out.contains("hello"))
      tester.workspacePath
    }
    integrationTest { tester =>
      moveCheckout(first, tester)
      tester.modifyFile(
        tester.workspacePath / "src/app/App.scala",
        _.replace("\"hello\"", "\"moved\"")
      )
      val res = tester.eval("run")
      assert(res.isSuccess)
      assert(res.out.contains("moved"))
      afterMove(tester)
    }
  }

  val tests: Tests = Tests {
    // A YAML build looks up its settings by the build file's path
    test("yamlBuild") - checkMove()
    // `build.mill` code sees its own file name through `sourcecode.File`
    test("millBuild") - checkMove(
      setup = ws => {
        os.remove(ws / "build.mill.yaml")
        os.write(
          ws / "build.mill",
          """package build
            |import mill.*, scalalib.*
            |object `package` extends ScalaModule {
            |  def scalaVersion = "3.8.2"
            |  def buildFile = Task.Input { summon[sourcecode.File].value }
            |}
            |""".stripMargin
        )
      },
      afterMove = tester => {
        // `buildFile` is an input, so this checks the compiled build code, not a cached value
        val res = tester.eval(("show", "buildFile"))
        assert(res.isSuccess)
        assert(os.Path(ujson.read(res.out).str) == tester.workspacePath / "build.mill")
      }
    )
  }
}
