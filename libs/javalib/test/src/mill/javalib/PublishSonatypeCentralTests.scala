package mill.javalib

import mill.api.{Evaluator, Task}
import mill.javalib.publish.SonatypeHelpers.{PASSWORD_ENV_VARIABLE_NAME, USERNAME_ENV_VARIABLE_NAME}
import mill.testkit.internal.SonatypeCentralTestUtils
import mill.util.Tasks
import utest.*

object PublishSonatypeCentralTests extends TestSuite {
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
    SonatypeCentralTestUtils.dryRunWithKey(
      task,
      dirName,
      secretBase64,
      passphrase,
      PublishSonatypeCentralTestModule,
      ResourcePath
    )
  }

  private def dryRun(task: Task[Unit], dirName: os.SubPath): Unit = {
    dryRunWithKey(task, dirName, TestPgpSecretBase64, None)
  }

  val tests: Tests = Tests {
    test("dryRun") {
      test("module") - dryRun(PublishTask, PublishDirName)
      test("externalModule") - dryRun(PublishAllTask, PublishAllDirName)
    }
  }
}
