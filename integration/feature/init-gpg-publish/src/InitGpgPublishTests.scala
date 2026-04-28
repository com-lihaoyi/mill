package mill.integration

import mill.constants.EnvVars
import mill.testkit.UtestIntegrationTestSuite
import mill.testkit.internal.SonatypeCentralTestUtils
import utest.*

import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}
import java.net.InetSocketAddress

object InitGpgPublishTests extends UtestIntegrationTestSuite {

  private def withMockKeyserver[T](f: String => T): T = {
    val server = HttpServer.create(new InetSocketAddress(0), 0)
    var uploadedKey: Option[String] = None

    server.createContext(
      "/pks/add",
      new HttpHandler {
        def handle(exchange: HttpExchange): Unit = {
          val reqBody = exchange.getRequestBody
          val body =
            try new String(reqBody.readAllBytes(), "UTF-8")
            finally reqBody.close()
          uploadedKey = Some(body)
          exchange.sendResponseHeaders(200, 0)
          exchange.getResponseBody.close()
        }
      }
    )

    server.createContext(
      "/pks/lookup",
      new HttpHandler {
        def handle(exchange: HttpExchange): Unit = {
          val response = uploadedKey match {
            case Some(key) if key.contains("keytext=") =>
              val decoded = java.net.URLDecoder.decode(
                key.split("keytext=")(1),
                "UTF-8"
              )
              decoded
            case _ =>
              "Not Found"
          }
          val responseBytes = response.getBytes("UTF-8")
          exchange.sendResponseHeaders(200, responseBytes.length)
          exchange.getResponseBody.write(responseBytes)
          exchange.getResponseBody.close()
        }
      }
    )

    server.start()
    val port = server.getAddress.getPort
    try f(s"http://localhost:$port")
    finally server.stop(0)
  }

  private def initGpgKeysSmokeTest(): Unit = integrationTest { tester =>
    import tester.*

    withMockKeyserver { keyserverUrl =>
      val res = eval(
        Seq(
          "mill.javalib.SonatypeCentralPublishModule/initGpgKeys",
          "--keyserverUrl",
          keyserverUrl
        ),
        stdin = Seq(
          "Mill Test User\n",
          "mill-test-user@example.com\n",
          "mill-test-passphrase\n"
        ).mkString,
        mergeErrIntoOut = true
      )
      println(res.debugString)
      assert(res.isSuccess)

      val output = res.out
      assert(output.contains("PGP Key Setup for Sonatype Central Publishing"))
      assert(output.contains("PGP key generated successfully"))
      assert(output.contains("Key verified on keyserver!"))
      assert(output.contains("Local Shell Configuration"))
      assert(output.contains("GitHub Actions"))
      assert(output.contains("MILL_PGP_SECRET_BASE64"))
      assert(output.contains("MILL_SONATYPE_USERNAME"))

      val armoredKey = os.read(
        workspacePath / "out" / "mill.javalib.SonatypeCentralPublishModule" /
          "initGpgKeys.dest" / "pgp-private-key.asc"
      ).trim
      assert(armoredKey.contains("BEGIN PGP PRIVATE KEY BLOCK"))

      val secretBase64 =
        java.util.Base64.getEncoder.encodeToString(armoredKey.getBytes("UTF-8"))
      val passphrase = "mill-test-passphrase"

      dryRunWithKey(
        tester,
        Seq(
          "mill.javalib.SonatypeCentralPublishModule/publishAll",
          "--publishArtifacts",
          "testProject.publishArtifacts"
        ),
        "mill.javalib.SonatypeCentralPublishModule/publishAll.dest",
        secretBase64,
        Some(passphrase)
      )
    }
  }

  val tests: Tests = Tests {
    test("initGpgKeys") - initGpgKeysSmokeTest()
  }

  private def dryRunWithKey(
      tester: mill.testkit.IntegrationTester,
      cmd: os.Shellable,
      dirName: os.SubPath,
      secretBase64: String,
      passphrase: Option[String]
  ): Unit = {
    import tester.*

    val env = baseDryRunEnv(Some(secretBase64), passphrase)
    val res = eval(cmd, env = env)
    println(res.debugString)
    assert(res.isSuccess)

    val dir = releaseRepoDir(
      workspacePath / "out" / dirName / "repository",
      group = "io.github.lihaoyi",
      artifactId = "testProject",
      version = "0.0.1"
    )
    val baseDir = dir / releaseGroupPath("io.github.lihaoyi") / "testProject" / "0.0.1"
    SonatypeCentralTestUtils.verifySignedArtifacts(
      baseDir,
      artifactId = "testProject",
      version = "0.0.1",
      secretBase64
    )
  }

  private def baseDryRunEnv(
      secretBase64: Option[String],
      passphrase: Option[String]
  ): Map[String, String] =
    Map(
      EnvVars.MILL_SONATYPE_USERNAME -> "mill-tests-username",
      EnvVars.MILL_SONATYPE_PASSWORD -> "mill-tests-password",
      "MILL_TESTS_PUBLISH_DRY_RUN" -> "1"
    ) ++ secretBase64.map(EnvVars.MILL_PGP_SECRET_BASE64 -> _) ++
      passphrase.map(EnvVars.MILL_PGP_PASSPHRASE -> _)

  private def releaseRepoDir(
      repoDir: os.Path,
      group: String,
      artifactId: String,
      version: String
  ): os.Path =
    repoDir / s"$group.$artifactId-$version"

  private def releaseGroupPath(group: String): os.SubPath =
    os.SubPath(group.split('.').toIndexedSeq)

}
