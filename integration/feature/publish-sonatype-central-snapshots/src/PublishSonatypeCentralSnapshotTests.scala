import mill.javalib.publish.SonatypeHelpers.{PASSWORD_ENV_VARIABLE_NAME, USERNAME_ENV_VARIABLE_NAME}
import mill.testkit.UtestIntegrationTestSuite
import utest.*

object PublishSonatypeCentralSnapshotTests extends UtestIntegrationTestSuite {
  private val PUBLISH_ORG_ENV_VARIABLE_NAME = "MILL_TESTS_PUBLISH_ORG"

  val tests: Tests = Tests {
    test("actual") - integrationTest { tester =>
      import tester.*

      val env = sys.env
      val maybePublishOrg = env.get(PUBLISH_ORG_ENV_VARIABLE_NAME)
      val maybePublishUsername = env.get(USERNAME_ENV_VARIABLE_NAME)
      val maybePublishPassword = env.get(PASSWORD_ENV_VARIABLE_NAME)

      (maybePublishOrg, maybePublishUsername, maybePublishPassword) match {
        case (Some(publishOrg), Some(publishUsername), Some(publishPassword)) =>
          val res = eval(
            "testProject.publishSonatypeCentral",
            env = Map(
              PUBLISH_ORG_ENV_VARIABLE_NAME -> publishOrg,
              USERNAME_ENV_VARIABLE_NAME -> publishUsername,
              PASSWORD_ENV_VARIABLE_NAME -> publishPassword
            )
          )
          println(
            s"""Success: ${res.isSuccess}
               |
               |stdout:
               |${res.out}
               |
               |stderr:
               |${res.err}
               |""".stripMargin
          )
          // Extract the values so that `assert` macro would print them out nicely if the test fails
          // instead of printing `res` twice.
          val isSuccess = res.isSuccess
          val err = res.err
          assert(isSuccess && err.contains("finished with result:"))

        case _ =>
          case class WithName[A](name: String, description: String, value: A)
          val missingEnvVars = Vector(
            WithName(
              PUBLISH_ORG_ENV_VARIABLE_NAME,
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
        Seq("testProject.publishSonatypeCentral", "--dry-run"),
        env = Map(
          PUBLISH_ORG_ENV_VARIABLE_NAME -> "io.github.mill_tests",
          USERNAME_ENV_VARIABLE_NAME -> "mill-tests-username",
          PASSWORD_ENV_VARIABLE_NAME -> "mill-tests-password"
        )
      )
      println(
        s"""Success: ${res.isSuccess}
           |
           |stdout:
           |${res.out}
           |
           |stderr:
           |${res.err}
           |""".stripMargin
      )
      // Extract the values so that `assert` macro would print them out nicely if the test fails
      // instead of printing `res` twice.
      val isSuccess = res.isSuccess
      val err = res.err
      assert(isSuccess && err.contains("finished with result:"))

      val metadataFile =
        workspacePath / "out" / "testProject" / "publishSonatypeCentral.dest" / "repository" / "io" / "github" /
          "mill_tests" / "testProject_3" / "maven-metadata.xml"
      assert(os.exists(metadataFile))

      val metadataContents = os.read(metadataFile)
      assert(metadataContents.contains("<version>0.0.1-SNAPSHOT</version>"))
    }
  }
}
