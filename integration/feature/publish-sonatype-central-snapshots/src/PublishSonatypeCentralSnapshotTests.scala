import mill.javalib.publish.SonatypeHelpers
import mill.testkit.UtestIntegrationTestSuite
import utest.*
import SonatypeHelpers.{USERNAME_ENV_VARIABLE_NAME, PASSWORD_ENV_VARIABLE_NAME}

object PublishSonatypeCentralSnapshotTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test("publish") - integrationTest { tester =>
      import tester.*

      val PUBLISH_ORG_ENV_VARIABLE_NAME = "MILL_TEST_PUBLISH_ORG"

      val maybePublishOrg = sys.props.get(PUBLISH_ORG_ENV_VARIABLE_NAME)
      val maybePublishUsername = sys.props.get(USERNAME_ENV_VARIABLE_NAME)
      val maybePublishPassword = sys.props.get(PASSWORD_ENV_VARIABLE_NAME)

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
          assert(res.out.contains("finished with result:"))
          assert(res.isSuccess)

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
