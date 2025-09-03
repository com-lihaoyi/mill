package mill.integration

import coursier.cache.FileCache
import mill.testkit.UtestIntegrationTestSuite
import utest._

import java.io.File

object CsEnvVarsTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test("cache") - integrationTest { tester =>
      import tester._

      def check(cacheOpt: Option[os.Path]): Unit = {
        val env = cacheOpt.toSeq.map { cache =>
          "COURSIER_CACHE" -> cache.toString
        }
        val res = eval(
          ("printRunClasspath"),
          env = env.toMap
        )
        assert(res.exitCode == 0)

        val cp = res.out.split(File.pathSeparator).filter(_.nonEmpty).map(os.Path(_))

        val actualCache = cacheOpt.getOrElse(os.Path(FileCache().location))
        assert(cp.exists(p => p.startsWith(actualCache) && p.last.startsWith("slf4j-api-")))
      }

      check(None)
      check(Some(workspacePath / "cache-0"))
      check(Some(workspacePath / "cache-1"))
    }

    test("mirrors") - integrationTest { tester =>
      import tester._

      def check(mirrorFileOpt: Option[os.Path]): Unit = {
        val env = mirrorFileOpt.toSeq.map { file =>
          "COURSIER_MIRRORS" -> file.toString
        }
        val res = eval(
          ("printRunClasspath"),
          env = env.toMap
        )
        assert(res.exitCode == 0)

        val cacheRoot = os.Path(FileCache().location)
        val cp = res.out.split(File.pathSeparator)
          .filter(_.nonEmpty)
          .map(os.Path(_))
          .filter(_.startsWith(cacheRoot))
          .map(_.relativeTo(cacheRoot).asSubPath)

        val centralReplaced = cp.forall { f =>
          f.startsWith(os.sub / "https/repo.maven.apache.org/maven2")
        }
        assert(cp.exists(f => f.last.startsWith("scala-library-") && f.last.endsWith(".jar")))
        assert(mirrorFileOpt.nonEmpty == centralReplaced)
      }

      check(None)

      val mirrorFile = workspacePath / "mirror.properties"
      os.write(
        mirrorFile,
        """central.from=https://repo1.maven.org/maven2
          |central.to=https://repo.maven.apache.org/maven2/
          |""".stripMargin
      )
      check(Some(mirrorFile))
    }
  }
}
