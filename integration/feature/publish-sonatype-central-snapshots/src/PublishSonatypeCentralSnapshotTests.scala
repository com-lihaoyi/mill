import mill.javalib.publish.SonatypeHelpers
import mill.testkit.UtestIntegrationTestSuite
import utest._
import SonatypeHelpers.{USERNAME_ENV_VARIABLE_NAME, PASSWORD_ENV_VARIABLE_NAME}

object PublishSonatypeCentralSnapshotTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test("publish") - integrationTest { tester =>
      import tester.*

      val PUBLISH_ORG_ENV_VARIABLE_NAME = "MILL_TESTS_PUBLISH_ORG"

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
            s"""Test is disabled because the following environment variables are not set:
               |${missingEnvVars.mkString("\n")}""".stripMargin
          )
      }
    }
  }
}
