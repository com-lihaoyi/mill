package mill.javalib

import mill.api.{Discover, Evaluator, Task}
import mill.javalib.publish.{Developer, License, PomSettings, VersionControl}
import mill.testkit.{TestRootModule, UnitTester}
import mill.testkit.internal.SonatypeCentralTestUtils
import mill.util.Tasks
import utest.*
import mill.util.TokenReaders.*
import mill.constants.EnvVars
object PublishSonatypeCentralTestModule extends TestRootModule {
  val TestPgpSecretBase64 =
    "LS0tLS1CRUdJTiBQR1AgUFJJVkFURSBLRVkgQkxPQ0stLS0tLQoKeFZnRWFHekhpeFlKS3dZQkJBSGFSdzhCQVFkQWxQamhsaGo5MUtZUnhDQXFtaUZNMjR1UEVDL0kxemR0CnlWS2dRR1lENHZZQUFQOW9jK0ZFQzQ2dkt6b0tNWVE3M1Jvemh4UDE3WWhUZnZwRFBwYk1CZHNZQ2c2RQp6VEpwYnk1bmFYUm9kV0l1WVhKMGRYSmhlaTUwWlhOMFVISnZhbVZqZENCaWIzUWdQR0Z6UUdGeWRIVnkKWVhvdWJtVjBQc0tNQkJBV0NnQWRCUUpvYk1lTEJBc0pCd2dERlFnS0JCWUFBZ0VDR1FFQ0d3TUNIZ0VBCklRa1FBMkRDK3lxemF1RVdJUVRnUmJWQ05LcVpxRTFkdDB3RFlNTDdLck5xNFR1L0FQNHRDYzZpYWNUdQpZVEJBa2Q3UDZOM1E1VTZjbGdnSElVQ2lRL3lIbmFvVHZ3RUExbU92M2MydEVORGtrdnF5Ujl2YVhWNHEKZlBEckNDRmRTUTR0anpMY3hnVEhYUVJvYk1lTEVnb3JCZ0VFQVpkVkFRVUJBUWRBUHpzMjV5RERLSC80Cm1KNmtMU1dLSExITXJEWUZMWGVHOTNWRTluSVY0Q0FEQVFnSEFBRC9aQ1hVMDhqMkZTU2VYQWdZaFZzNwp2akVDQjQweTA2TjdaM0pqaitCSko3Z08xc0o0QkJnV0NBQUpCUUpvYk1lTEFoc01BQ0VKRUFOZ3d2c3EKczJyaEZpRUU0RVcxUWpTcW1haE5YYmRNQTJEQyt5cXphdUgrY2dEL1QxRUVkVDl1WnR6L255bGk1OHR0CjYxaWNLcndyU3kzSTBBRDNYWWErcm40QS9qWEZlZXNsNVBZZWtpU0ZzNVZGNUczRVNpWmY0amJxZXlOWQpLd09ENVIwSwo9WDhSdQotLS0tLUVORCBQR1AgUFJJVkFURSBLRVkgQkxPQ0stLS0tLQo="

  trait MyModule extends JavaModule with SonatypeCentralPublishModule {


    def pomSettings = Task {
      PomSettings(
        description = "Hello",
        organization = "io.github.lihaoyi",
        url = "https://github.com/lihaoyi/example",
        licenses = Seq(License.MIT),
        versionControl = VersionControl.github("lihaoyi", "example"),
        developers = Seq(Developer("lihaoyi", "Li Haoyi", "https://github.com/lihaoyi"))
      )
    }
  }
  object normal extends MyModule {
    def publishVersion = "0.0.1"
  }

  object snapshot extends MyModule {
    def publishVersion = "0.0.1-SNAPSHOT"
  }

  lazy val millDiscover = Discover[this.type]
}

object PublishSonatypeCentralTests extends TestSuite {
  val ResourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "publish-sonatype-central"

  val tests: Tests = Tests {
    test("normal") {
      def dryRun(task: Task[Unit], dirName: os.SubPath): Unit = {
        dryRunWithKey(
          task,
          dirName,
          Some(PublishSonatypeCentralTestModule.TestPgpSecretBase64),
          None,
          PublishSonatypeCentralTestModule,
          ResourcePath
        ) { repoDir =>
          val dir = releaseRepoDir(
            repoDir,
            group = "io.github.lihaoyi",
            artifactId = "normal",
            version = "0.0.1"
          )
          val baseDir = dir / releaseGroupPath("io.github.lihaoyi") / "normal" / "0.0.1"
          val expectedFiles = releaseExpectedFiles(baseDir, "normal-0.0.1")
          val actualFiles = os.walk(dir).toVector
          val missingFiles = expectedFiles.filterNot(actualFiles.contains)
          assert(missingFiles.isEmpty)

          SonatypeCentralTestUtils.verifySignedArtifacts(
            baseDir,
            artifactId = "normal",
            version = "0.0.1",
            PublishSonatypeCentralTestModule.TestPgpSecretBase64
          )
        }
      }
      test("module") - dryRun(
        PublishSonatypeCentralTestModule.normal.publishSonatypeCentral(),
        "normal/publishSonatypeCentral.dest"
      )
      test("externalModule") - dryRun(
        SonatypeCentralPublishModule.publishAll(
          publishArtifacts = Tasks(Seq(PublishSonatypeCentralTestModule.normal.publishArtifacts))
        ),
        "mill.javalib.SonatypeCentralPublishModule/publishAll.dest"
      )
    }
    test("snapshot") {
      def dryRun(task: Task[Unit], dirName: os.SubPath): Unit =
        dryRunWithKey(
          task,
          dirName,
          Some(PublishSonatypeCentralTestModule.TestPgpSecretBase64),
          None,
          PublishSonatypeCentralTestModule,
          ResourcePath
        ) { repoDir =>
          SonatypeCentralTestUtils.assertSnapshotRepository(
            repoDir,
            group = "io.github.lihaoyi",
            artifactId = "snapshot",
            version = "0.0.1-SNAPSHOT"
          )
        }

      test("module") - dryRun(
        PublishSonatypeCentralTestModule.snapshot.publishSonatypeCentral(),
        "snapshot/publishSonatypeCentral.dest"
      )
      test("externalModule") - dryRun(
        SonatypeCentralPublishModule.publishAll(
          publishArtifacts = Tasks(Seq(PublishSonatypeCentralTestModule.snapshot.publishArtifacts))
        ),
        "mill.javalib.SonatypeCentralPublishModule/publishAll.dest"
      )
    }
  }

  private def dryRunWithKey(
      task: Task[Unit],
      dirName: os.SubPath,
      secretBase64: Option[String],
      passphrase: Option[String],
      module: TestRootModule,
      resourcePath: os.Path
  )(validateRepo: os.Path => Unit): Unit = {
    val env = baseDryRunEnv(secretBase64, passphrase)
    UnitTester(
      module,
      resourcePath,
      env = Evaluator.defaultEnv ++ env
    ).scoped { eval =>
      val Right(_) = eval.apply(task).runtimeChecked
      val workspacePath = module.moduleDir
      val repoDir = workspacePath / "out" / dirName / "repository"
      validateRepo(repoDir)
    }
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

  private def releaseExpectedFiles(
      dir: os.Path,
      baseName: String
  ): Vector[os.Path] =
    releaseArtifactBaseFiles(dir, baseName).flatMap { file =>
      Vector(
        file,
        os.Path(file.toString + ".asc"),
        os.Path(file.toString + ".asc.md5"),
        os.Path(file.toString + ".asc.sha1"),
        os.Path(file.toString + ".md5"),
        os.Path(file.toString + ".sha1")
      )
    }

  private def releaseArtifactBaseFiles(
      dir: os.Path,
      baseName: String
  ): Vector[os.Path] =
    Vector(".jar", "-sources.jar", "-javadoc.jar", ".pom").map(suffix => dir / s"$baseName$suffix")
}
