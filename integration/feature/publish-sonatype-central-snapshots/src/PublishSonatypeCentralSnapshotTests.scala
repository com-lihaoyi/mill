import mill.javalib.publish.SonatypeHelpers.{PASSWORD_ENV_VARIABLE_NAME, USERNAME_ENV_VARIABLE_NAME}
import mill.testkit.UtestIntegrationTestSuite
import utest.*

object PublishSonatypeCentralSnapshotTests extends UtestIntegrationTestSuite {
  private val ENV_VAR_PUBLISH_ORG = "MILL_TESTS_PUBLISH_ORG"
  private val ENV_VAR_DRY_RUN = "MILL_TESTS_PUBLISH_DRY_RUN"

  val tests: Tests = Tests {
    test("actual") - integrationTest { tester =>
      import tester.*

      val env = sys.env
      val maybePublishOrg = env.get(ENV_VAR_PUBLISH_ORG)
      val maybePublishUsername = env.get(USERNAME_ENV_VARIABLE_NAME)
      val maybePublishPassword = env.get(PASSWORD_ENV_VARIABLE_NAME)

      (maybePublishOrg, maybePublishUsername, maybePublishPassword) match {
        case (Some(publishOrg), Some(publishUsername), Some(publishPassword)) =>
          val res = eval(
            "testProject.publishSonatypeCentral",
            env = Map(
              ENV_VAR_PUBLISH_ORG -> publishOrg,
              USERNAME_ENV_VARIABLE_NAME -> publishUsername,
              PASSWORD_ENV_VARIABLE_NAME -> publishPassword
            )
          )
          println(res.debugString)
          // Extract the values so that `assert` macro would print them out nicely if the test fails
          // instead of printing `res` twice.
          val isSuccess = res.isSuccess
          val err = res.err
          assert(isSuccess && err.contains("finished with result:"))

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

    test("dryRun") - integrationTest { tester =>
      import tester.*

      val res = eval(
        "testProject.publishSonatypeCentral",
        env = Map(
          ENV_VAR_PUBLISH_ORG -> "io.github.mill_tests",
          USERNAME_ENV_VARIABLE_NAME -> "mill-tests-username",
          PASSWORD_ENV_VARIABLE_NAME -> "mill-tests-password",
          ENV_VAR_DRY_RUN -> "1"
        )
      )
      println(res.debugString)
      // Extract the values so that `assert` macro would print them out nicely if the test fails
      // instead of printing `res` twice.
      val isSuccess = res.isSuccess
      val err = res.err
      assert(isSuccess && err.contains("finished with result:"))

      val publishedDir =
        workspacePath / "out" / "testProject" / "publishSonatypeCentral.dest" / "repository" / "io" / "github" /
          "mill_tests" / "testProject_3"

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
      val timestamp = timestampRegex.findFirstMatchIn(metadataContents).map(_.group(1)).getOrElse{
        throw new Exception(s"No timestamp found via $timestampRegex in $metadataFile:\n$metadataContents")
      }

      val expectedFiles = Vector(
        rootMetadataFile,
        publishedDir / "maven-metadata.xml.md5",
        publishedDir / "maven-metadata.xml.sha1",
        metadataFile,
        publishedVersionDir / "maven-metadata.xml.md5",
        publishedVersionDir / "maven-metadata.xml.sha1",
        publishedVersionDir / s"testProject_3-0.0.1-$timestamp-1.jar",
        publishedVersionDir / s"testProject_3-0.0.1-$timestamp-1.jar.md5",
        publishedVersionDir / s"testProject_3-0.0.1-$timestamp-1.jar.sha1",
        publishedVersionDir / s"testProject_3-0.0.1-$timestamp-1.pom",
        publishedVersionDir / s"testProject_3-0.0.1-$timestamp-1.pom.md5",
        publishedVersionDir / s"testProject_3-0.0.1-$timestamp-1.pom.sha1",
      )
      val actualFiles = os.walk(publishedDir).toVector
      val missingFiles = expectedFiles.filterNot(actualFiles.contains)
      assert(missingFiles.isEmpty)
    }
  }
}
