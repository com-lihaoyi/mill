package mill.javalib

import mill.api.{Evaluator, Task}
import mill.javalib.publish.SonatypeHelpers.{PASSWORD_ENV_VARIABLE_NAME, USERNAME_ENV_VARIABLE_NAME}
import mill.testkit.UnitTester
import mill.util.Tasks
import utest.*

object PublishSonatypeCentralSnapshotTests extends TestSuite {
  import PublishSonatypeCentralTestUtils.*

  private val ENV_VAR_PUBLISH_ORG = "MILL_TESTS_PUBLISH_ORG"
  private val ENV_VAR_DRY_RUN = "MILL_TESTS_PUBLISH_DRY_RUN"
  private val PublishTask = PublishSonatypeCentralTestModule.testProject.publishSonatypeCentral()
  private val PublishDirName = os.SubPath("testProject/publishSonatypeCentral.dest")
  private val PublishAllTask = SonatypeCentralPublishModule.publishAll(
    publishArtifacts = Tasks(Seq(PublishSonatypeCentralTestModule.testProject.publishArtifacts))
  )
  private val PublishAllDirName =
    os.SubPath("mill.javalib.SonatypeCentralPublishModule/publishAll.dest")
  private val ResourcePath =
    os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "publish-sonatype-central"

  private def actual(task: Task[Unit]): Unit = {
    val env = sys.env
    val maybePublishOrg = env.get(ENV_VAR_PUBLISH_ORG)
    val maybePublishUsername = env.get(USERNAME_ENV_VARIABLE_NAME)
    val maybePublishPassword = env.get(PASSWORD_ENV_VARIABLE_NAME)

    (maybePublishOrg, maybePublishUsername, maybePublishPassword) match {
      case (Some(publishOrg), Some(publishUsername), Some(publishPassword)) =>
        UnitTester(
          PublishSonatypeCentralTestModule,
          ResourcePath,
          env = Evaluator.defaultEnv ++ Map(
            ENV_VAR_PUBLISH_ORG -> publishOrg,
            USERNAME_ENV_VARIABLE_NAME -> publishUsername,
            PASSWORD_ENV_VARIABLE_NAME -> publishPassword
          )
        ).scoped { eval =>
          val Right(_) = eval.apply(task).runtimeChecked
        }

      case _ =>
        case class WithName[A](name: String, description: String, value: A)
        val missingEnvVars = Vector(
          WithName(
            ENV_VAR_PUBLISH_ORG,
            "The organization to publish to",
            maybePublishOrg
          ),
          WithName(USERNAME_ENV_VARIABLE_NAME, "Sonatype Central username", maybePublishUsername),
          WithName(PASSWORD_ENV_VARIABLE_NAME, "Sonatype Central password", maybePublishPassword)
        ).filter(_.value.isEmpty).map(v => s"${v.name} (${v.description})")

        println(
          s"""Test is disabled by default (due to the potential flakyness and slowness of Sonatype Central).
             |
             |To enable this test, set the following environment variables:
             |${missingEnvVars.mkString("\n")}""".stripMargin
        )
    }
  }

  private def dryRun(task: Task[Unit], dirName: os.SubPath): Unit = {
    UnitTester(
      PublishSonatypeCentralTestModule,
      ResourcePath,
      env = Evaluator.defaultEnv ++ Map(
        ENV_VAR_PUBLISH_ORG -> "io.github.mill_tests",
        USERNAME_ENV_VARIABLE_NAME -> "mill-tests-username",
        PASSWORD_ENV_VARIABLE_NAME -> "mill-tests-password",
        ENV_VAR_DRY_RUN -> "1"
      )
    ).scoped { eval =>
      val Right(_) = eval.apply(task).runtimeChecked
      val workspacePath = PublishSonatypeCentralTestModule.moduleDir

      val publishedDir =
        workspacePath / "out" / dirName / "repository" / "io" / "github" / "mill_tests" / "testProject"

      val rootMetadataFile = publishedDir / "maven-metadata.xml"
      assert(os.exists(rootMetadataFile))

      val rootMetadataContents = os.read(rootMetadataFile)
      assert(rootMetadataContents.contains("<version>0.0.1-SNAPSHOT</version>"))

      val publishedVersionDir = publishedDir / "0.0.1-SNAPSHOT"

      val metadataFile = publishedVersionDir / "maven-metadata.xml"
      assert(os.exists(metadataFile))

      val metadataContents: String = os.read(metadataFile)
      assert(metadataContents.contains("<version>0.0.1-SNAPSHOT</version>"))

      val timestampRegex = """<timestamp>(\d{8}\.\d{6})</timestamp>""".r
      val timestamp = timestampRegex.findFirstMatchIn(metadataContents).map(_.group(1)).getOrElse {
        throw new Exception(
          s"No timestamp found via $timestampRegex in $metadataFile:\n$metadataContents"
        )
      }

      val expectedFiles = Vector(
        rootMetadataFile,
        publishedDir / "maven-metadata.xml.md5",
        publishedDir / "maven-metadata.xml.sha1",
        metadataFile,
        publishedVersionDir / "maven-metadata.xml.md5",
        publishedVersionDir / "maven-metadata.xml.sha1",
        publishedVersionDir / s"testProject-0.0.1-$timestamp-1.jar",
        publishedVersionDir / s"testProject-0.0.1-$timestamp-1.jar.md5",
        publishedVersionDir / s"testProject-0.0.1-$timestamp-1.jar.sha1",
        publishedVersionDir / s"testProject-0.0.1-$timestamp-1-sources.jar",
        publishedVersionDir / s"testProject-0.0.1-$timestamp-1-sources.jar.md5",
        publishedVersionDir / s"testProject-0.0.1-$timestamp-1-sources.jar.sha1",
        publishedVersionDir / s"testProject-0.0.1-$timestamp-1-javadoc.jar",
        publishedVersionDir / s"testProject-0.0.1-$timestamp-1-javadoc.jar.md5",
        publishedVersionDir / s"testProject-0.0.1-$timestamp-1-javadoc.jar.sha1",
        publishedVersionDir / s"testProject-0.0.1-$timestamp-1.pom",
        publishedVersionDir / s"testProject-0.0.1-$timestamp-1.pom.md5",
        publishedVersionDir / s"testProject-0.0.1-$timestamp-1.pom.sha1"
      )
      val actualFiles = os.walk(publishedDir).toVector
      val missingFiles = expectedFiles.filterNot(actualFiles.contains)
      assert(missingFiles.isEmpty)

      val checksumTargets = Vector(
        publishedDir / "maven-metadata.xml",
        publishedVersionDir / "maven-metadata.xml",
        publishedVersionDir / s"testProject-0.0.1-$timestamp-1.jar",
        publishedVersionDir / s"testProject-0.0.1-$timestamp-1-sources.jar",
        publishedVersionDir / s"testProject-0.0.1-$timestamp-1-javadoc.jar",
        publishedVersionDir / s"testProject-0.0.1-$timestamp-1.pom"
      )
      checksumTargets.foreach { file =>
        assertChecksumMatches(file, os.Path(file.toString + ".md5"), "MD5")
        assertChecksumMatches(file, os.Path(file.toString + ".sha1"), "SHA1")
      }
    }
  }

  val tests: Tests = Tests {
    test("actual") {
      test("module") - actual(PublishTask)
      test("externalModule") - actual(PublishAllTask)
    }

    test("dryRun") {
      test("module") - dryRun(PublishTask, PublishDirName)
      test("externalModule") - dryRun(PublishAllTask, PublishAllDirName)
    }
  }
}
