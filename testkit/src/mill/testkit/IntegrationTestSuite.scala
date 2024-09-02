package mill.testkit

abstract class IntegrationTestSuite extends IntegrationTestSuiteBase with IntegrationTester.Impl {
  override def utestAfterEach(path: Seq[String]): Unit = {
    if (clientServerMode) close()
  }
}
