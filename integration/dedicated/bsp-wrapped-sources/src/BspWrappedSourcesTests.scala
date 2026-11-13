package mill.integration

import mill.constants.CodeGenConstants
import mill.integration.BspServerTestUtil.*
import mill.testkit.UtestIntegrationTestSuite
import utest.*

import java.net.URI
import java.nio.file.Paths
import scala.build.bsp.WrappedSourcesParams
import scala.jdk.CollectionConverters.*

/**
 * Checks the wrapped sources Mill reports via BSP for the different kinds of build files
 * (root `build.mill`, `package.mill` with and without an object `package`, sibling scripts),
 * against what code generation actually writes.
 */
object BspWrappedSourcesTests extends UtestIntegrationTestSuite {
  def tests: Tests = Tests {
    test("wrappedSources") - integrationTest { tester =>
      import tester.*
      eval(
        ("--bsp-install", "--jobs", "1"),
        stdout = os.Inherit,
        stderr = os.Inherit,
        check = true,
        env = Map("MILL_EXECUTABLE_PATH" -> tester.millExecutable.toString)
      )

      withBspServer(workspacePath, millTestSuiteEnv) { (buildServer, _) =>
        val targetIds = buildServer.workspaceBuildTargets().get().getTargets.asScala.map(_.getId)
        val result = buildServer
          .buildTargetWrappedSources(WrappedSourcesParams(targetIds.asJava))
          .get()

        def path(uri: String) = os.Path(Paths.get(URI(uri)))

        val sources = result.getItems.asScala.toSeq
          .flatMap(_.getSources.asScala)
          .map(item => path(item.getUri).relativeTo(workspacePath).asSubPath -> item)
          .toMap

        assert(sources.keySet == Set(
          os.sub / "build.mill",
          os.sub / "helpers.mill",
          os.sub / "thing/package.mill",
          os.sub / "other/package.mill"
        ))

        for ((subPath, item) <- sources) {
          val original = os.read(workspacePath / subPath)
          val generated = os.read(path(item.getGeneratedUri))
          val top = item.getTopWrapper
          val bottom = item.getBottomWrapper

          // The wrappers frame the code generated out of the build file
          assert(generated.startsWith(top))
          assert(generated.endsWith(bottom))
          val userCode = generated.drop(top.length).dropRight(bottom.length)

          // Code generation only makes length-preserving edits to the user code,
          // so it has the same lines as the build file
          assert(userCode.length == original.length)
          assert(userCode.linesIterator.map(_.length).toSeq ==
            original.linesIterator.map(_.length).toSeq)

          // The top wrapper ends with the marker line, pointing at the build file
          val markerLine = top.linesIterator.toSeq.last
          assert(markerLine == CodeGenConstants.userCodeStartMarker + (workspacePath / subPath))
        }

        // The bottom wrappers depend on the kind of build file. Code generation drops the
        // trailing new line of the build file, and adds its own before the bottom wrapper.
        assert(sources(os.sub / "build.mill").getBottomWrapper == "}")
        assert(sources(os.sub / "other/package.mill").getBottomWrapper == "}")
        assert(sources(os.sub / "helpers.mill").getBottomWrapper == "}\n\nexport helpers_._\n")
        // object `package` parents get moved to a stub class after the user code, whose name
        // is cut to the length of the parents
        val thingBottom = sources(os.sub / "thing/package.mill").getBottomWrapper
        assert(thingBottom.trim.startsWith("abstract class _MillRootM"))
        assert(thingBottom.trim.endsWith(", JavaModule"))
      }
    }
  }
}
