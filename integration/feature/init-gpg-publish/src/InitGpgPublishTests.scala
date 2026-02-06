package mill.integration

import mill.constants.EnvVars
import mill.testkit.UtestIntegrationTestSuite
import mill.testkit.internal.SonatypeCentralTestUtils
import utest.*

object InitGpgPublishTests extends UtestIntegrationTestSuite {

  private def initGpgKeysSmokeTest(): Unit = integrationTest { tester =>
    import tester.*

    val res = eval(
      "mill.javalib.SonatypeCentralPublishModule/initGpgKeys",
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
