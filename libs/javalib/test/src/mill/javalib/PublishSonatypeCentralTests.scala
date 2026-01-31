package mill.javalib

import mill.api.{Evaluator, Task}
import mill.javalib.publish.SonatypeHelpers.{PASSWORD_ENV_VARIABLE_NAME, USERNAME_ENV_VARIABLE_NAME}
import mill.testkit.UnitTester
import mill.util.Tasks
import utest.*

object PublishSonatypeCentralTests extends TestSuite {
  import PublishSonatypeCentralTestUtils.*

  private val ENV_VAR_DRY_RUN = "MILL_TESTS_PUBLISH_DRY_RUN"
  private val PublishTask = PublishSonatypeCentralTestModule.testProject.publishSonatypeCentral()
  private val PublishDirName = os.SubPath("testProject/publishSonatypeCentral.dest")
  private val PublishAllTask = SonatypeCentralPublishModule.publishAll(
    publishArtifacts = Tasks(Seq(PublishSonatypeCentralTestModule.testProject.publishArtifacts))
  )
  private val PublishAllDirName =
    os.SubPath("mill.javalib.SonatypeCentralPublishModule/publishAll.dest")
  private val TestPgpSecretBase64 =
    "LS0tLS1CRUdJTiBQR1AgUFJJVkFURSBLRVkgQkxPQ0stLS0tLQoKeFZnRWFHekhpeFlKS3dZQkJBSGFSdzhCQVFkQWxQamhsaGo5MUtZUnhDQXFtaUZNMjR1UEVDL0kxemR0CnlWS2dRR1lENHZZQUFQOW9jK0ZFQzQ2dkt6b0tNWVE3M1Jvemh4UDE3WWhUZnZwRFBwYk1CZHNZQ2c2RQp6VEpwYnk1bmFYUm9kV0l1WVhKMGRYSmhlaTUwWlhOMFVISnZhbVZqZENCaWIzUWdQR0Z6UUdGeWRIVnkKWVhvdWJtVjBQc0tNQkJBV0NnQWRCUUpvYk1lTEJBc0pCd2dERlFnS0JCWUFBZ0VDR1FFQ0d3TUNIZ0VBCklRa1FBMkRDK3lxemF1RVdJUVRnUmJWQ05LcVpxRTFkdDB3RFlNTDdLck5xNFR1L0FQNHRDYzZpYWNUdQpZVEJBa2Q3UDZOM1E1VTZjbGdnSElVQ2lRL3lIbmFvVHZ3RUExbU92M2MydEVORGtrdnF5Ujl2YVhWNHEKZlBEckNDRmRTUTR0anpMY3hnVEhYUVJvYk1lTEVnb3JCZ0VFQVpkVkFRVUJBUWRBUHpzMjV5RERLSC80Cm1KNmtMU1dLSExITXJEWUZMWGVHOTNWRTluSVY0Q0FEQVFnSEFBRC9aQ1hVMDhqMkZTU2VYQWdZaFZzNwp2akVDQjQweTA2TjdaM0pqaitCSko3Z08xc0o0QkJnV0NBQUpCUUpvYk1lTEFoc01BQ0VKRUFOZ3d2c3EKczJyaEZpRUU0RVcxUWpTcW1haE5YYmRNQTJEQyt5cXphdUgrY2dEL1QxRUVkVDl1WnR6L255bGk1OHR0CjYxaWNLcndyU3kzSTBBRDNYWWErcm40QS9qWEZlZXNsNVBZZWtpU0ZzNVZGNUczRVNpWmY0amJxZXlOWQpLd09ENVIwSwo9WDhSdQotLS0tLUVORCBQR1AgUFJJVkFURSBLRVkgQkxPQ0stLS0tLQo="
  private val ResourcePath =
    os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "publish-sonatype-central"

  private def dryRunWithKey(
      task: Task[Unit],
      dirName: os.SubPath,
      secretBase64: String,
      passphrase: Option[String]
  ): Unit = {
    val env = Map(
      USERNAME_ENV_VARIABLE_NAME -> "mill-tests-username",
      PASSWORD_ENV_VARIABLE_NAME -> "mill-tests-password",
      ENV_VAR_DRY_RUN -> "1",
      "MILL_PGP_SECRET_BASE64" -> secretBase64
    ) ++ passphrase.map("MILL_PGP_PASSPHRASE" -> _)
    UnitTester(
      PublishSonatypeCentralTestModule,
      ResourcePath,
      env = Evaluator.defaultEnv ++ env
    ).scoped { eval =>
      val Right(_) = eval.apply(task).runtimeChecked
      val workspacePath = PublishSonatypeCentralTestModule.moduleDir

      val dir =
        workspacePath / "out" / dirName / "repository" / "io.github.lihaoyi.testProject-0.0.1"

      val expectedFiles = Vector(
        dir / "io/github/lihaoyi/testProject/0.0.1/testProject-0.0.1-javadoc.jar",
        dir / "io/github/lihaoyi/testProject/0.0.1/testProject-0.0.1-javadoc.jar.asc",
        dir / "io/github/lihaoyi/testProject/0.0.1/testProject-0.0.1-javadoc.jar.asc.md5",
        dir / "io/github/lihaoyi/testProject/0.0.1/testProject-0.0.1-javadoc.jar.asc.sha1",
        dir / "io/github/lihaoyi/testProject/0.0.1/testProject-0.0.1-javadoc.jar.md5",
        dir / "io/github/lihaoyi/testProject/0.0.1/testProject-0.0.1-javadoc.jar.sha1",
        dir / "io/github/lihaoyi/testProject/0.0.1/testProject-0.0.1-sources.jar",
        dir / "io/github/lihaoyi/testProject/0.0.1/testProject-0.0.1-sources.jar.asc",
        dir / "io/github/lihaoyi/testProject/0.0.1/testProject-0.0.1-sources.jar.asc.md5",
        dir / "io/github/lihaoyi/testProject/0.0.1/testProject-0.0.1-sources.jar.asc.sha1",
        dir / "io/github/lihaoyi/testProject/0.0.1/testProject-0.0.1-sources.jar.md5",
        dir / "io/github/lihaoyi/testProject/0.0.1/testProject-0.0.1-sources.jar.sha1",
        dir / "io/github/lihaoyi/testProject/0.0.1/testProject-0.0.1.jar",
        dir / "io/github/lihaoyi/testProject/0.0.1/testProject-0.0.1.jar.asc",
        dir / "io/github/lihaoyi/testProject/0.0.1/testProject-0.0.1.jar.asc.md5",
        dir / "io/github/lihaoyi/testProject/0.0.1/testProject-0.0.1.jar.asc.sha1",
        dir / "io/github/lihaoyi/testProject/0.0.1/testProject-0.0.1.jar.md5",
        dir / "io/github/lihaoyi/testProject/0.0.1/testProject-0.0.1.jar.sha1",
        dir / "io/github/lihaoyi/testProject/0.0.1/testProject-0.0.1.pom",
        dir / "io/github/lihaoyi/testProject/0.0.1/testProject-0.0.1.pom.asc",
        dir / "io/github/lihaoyi/testProject/0.0.1/testProject-0.0.1.pom.asc.md5",
        dir / "io/github/lihaoyi/testProject/0.0.1/testProject-0.0.1.pom.asc.sha1",
        dir / "io/github/lihaoyi/testProject/0.0.1/testProject-0.0.1.pom.md5",
        dir / "io/github/lihaoyi/testProject/0.0.1/testProject-0.0.1.pom.sha1"
      )
      val actualFiles = os.walk(dir).toVector
      val missingFiles = expectedFiles.filterNot(actualFiles.contains)
      assert(missingFiles.isEmpty)

      val signTargets = Vector(
        dir / "io/github/lihaoyi/testProject/0.0.1/testProject-0.0.1-javadoc.jar",
        dir / "io/github/lihaoyi/testProject/0.0.1/testProject-0.0.1-sources.jar",
        dir / "io/github/lihaoyi/testProject/0.0.1/testProject-0.0.1.jar",
        dir / "io/github/lihaoyi/testProject/0.0.1/testProject-0.0.1.pom"
      )
      withGpgHome(secretBase64) { gpgEnv =>
        signTargets.foreach { file =>
          assertSignatureValid(file, gpgEnv)
          assertChecksumMatches(file, os.Path(file.toString + ".md5"), "MD5", gpgEnv)
          assertChecksumMatches(file, os.Path(file.toString + ".sha1"), "SHA1", gpgEnv)
        }
      }
    }
  }

  private def dryRun(task: Task[Unit], dirName: os.SubPath): Unit = {
    dryRunWithKey(task, dirName, TestPgpSecretBase64, None)
  }

  private def withGpgHome[T](secretKeyBase64: String)(f: Map[String, String] => T): T = {
    val gpgHome = os.temp.dir(prefix = "mill-gpg")
    val gpgEnv = Map("GNUPGHOME" -> gpgHome.toString)
    val secretBytes = java.util.Base64.getDecoder.decode(secretKeyBase64)
    os.proc("gpg", "--batch", "--yes", "--import").call(stdin = secretBytes, env = gpgEnv)
    f(gpgEnv)
  }

  private def assertSignatureValid(file: os.Path, gpgEnv: Map[String, String]): Unit = {
    val signature = os.Path(file.toString + ".asc")
    os.proc("gpg", "--batch", "--verify", signature.toString, file.toString)
      .call(env = gpgEnv)
  }

  val tests: Tests = Tests {
    test("dryRun") {
      test("module") - dryRun(PublishTask, PublishDirName)
      test("externalModule") - dryRun(PublishAllTask, PublishAllDirName)
    }
  }
}
