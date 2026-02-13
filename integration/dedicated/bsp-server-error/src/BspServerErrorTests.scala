package mill.integration

import ch.epfl.scala.bsp4j as b
import mill.integration.BspServerTestUtil.*
import mill.testkit.UtestIntegrationTestSuite
import utest.*
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutionException

object BspServerErrorTests extends UtestIntegrationTestSuite {
  def tests: Tests = Tests {
    test("errorTest") - integrationTest { tester =>
      import tester.*
      eval(
        ("--bsp-install", "--jobs", "1"),
        stdout = os.Inherit,
        stderr = os.Inherit,
        check = true,
        env = Map("MILL_EXECUTABLE_PATH" -> tester.millExecutable.toString)
      )

      val stderr = new ByteArrayOutputStream
      withBspServer(
        workspacePath,
        millTestSuiteEnv,
        bspLog = Some((bytes, len) => stderr.write(bytes, 0, len))
      ) { (buildServer, initRes) =>

        assert(initRes.getCapabilities.getInverseSourcesProvider == true)

        val file = workspacePath / "hello-scala/src/Hello.scala"
        assert(os.exists(file))

        val res =
          try
            Right {
              buildServer
                .buildTargetInverseSources(
                  new b.InverseSourcesParams(
                    new b.TextDocumentIdentifier(file.toURI.toASCIIString)
                  )
                )
                .get()
            }
          catch {
            case _: ExecutionException =>
              Left(new String(stderr.toByteArray))
          }

        assert(res.isLeft)
        assert(res.left.exists(_.contains("bad-dep.resolvedMvnDeps")))
        assert(res.left.exists(_.contains("Resolution failed for 1 modules:")))
        assert(res.left.exists(_.contains("junit:junit:14.0")))
        // look for some stack trace bits
        assert(res.left.exists(_.contains("(CoursierModule.scala:")))
        assert(res.left.exists(_.contains("(JavaModule.scala")))
      }
    }
  }
}
