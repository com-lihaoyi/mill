package mill.testkit
import mill.api.Retry

import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}

abstract class UtestIntegrationTestSuite extends utest.TestSuite with IntegrationTestSuite {
  protected def workspaceSourcePath: os.Path = os.Path(sys.env("MILL_TEST_RESOURCE_DIR"))
  protected def clientServerMode: Boolean = sys.env("MILL_INTEGRATION_SERVER_MODE").toBoolean

  /** Whether the Mill JARs are published locally alongside this Mill launcher */
  protected def isPackagedLauncher: Boolean =
    sys.env("MILL_INTEGRATION_IS_PACKAGED_LAUNCHER").toBoolean
  protected def millExecutable: os.Path =
    os.Path(System.getenv("MILL_INTEGRATION_LAUNCHER"), os.pwd)

  override def utestWrap(path: Seq[String], runBody: => Future[Any])(implicit
      ec: ExecutionContext
  ): Future[Any] = {
    Retry(
      count = if (sys.env.contains("CI")) 1 else 0,
      timeoutMillis = 10.minutes.toMillis
    ) {
      super.utestWrap(path, runBody)
    }
  }
}
