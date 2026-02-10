package mill.javalib.testrunner

import mill.api.daemon.internal.{TestReporter, internal}

@internal object MillTestRunnerMain0 {
  private val unmangledPathSerializer: os.Path.Serializer = new os.Path.Serializer {
    def serializeString(p: os.Path): String = p.wrapped.toString
    def serializeFile(p: os.Path): java.io.File = p.wrapped.toFile
    def serializePath(p: os.Path): java.nio.file.Path = p.wrapped
    def deserialize(s: String): java.nio.file.Path = java.nio.file.Paths.get(s)
    def deserialize(s: java.io.File): java.nio.file.Path = java.nio.file.Paths.get(s.getPath)
    def deserialize(s: java.nio.file.Path): java.nio.file.Path = s
    def deserialize(s: java.net.URI): java.nio.file.Path = java.nio.file.Paths.get(s)
  }

  def main0(args: Array[String], classLoader: ClassLoader): Unit = {
    try {
      os.Path.pathSerializer.withValue(unmangledPathSerializer) {
        val testArgs = upickle.read[TestArgs](os.read(os.Path(args(1), os.pwd)))
        testArgs.sysProps.foreach { case (k, v) => System.setProperty(k, v) }

        val result = testArgs.globSelectors match {
          case Left(selectors) =>
            val filter = TestRunnerUtils.globFilter(selectors)
            TestRunnerUtils.runTestFramework0(
              frameworkInstances = Framework.framework(testArgs.framework),
              testClassfilePath = Seq.from(testArgs.testCp),
              args = testArgs.arguments,
              classFilter = cls => filter(cls.getName),
              cl = classLoader,
              testReporter = TestReporter(testArgs.logLevel),
              resultPathOpt = Some(testArgs.resultPath)
            )
          case Right((startingTestClass, testClassQueueFolder, claimFolder)) =>
            TestRunnerUtils.queueTestFramework0(
              frameworkInstances = Framework.framework(testArgs.framework),
              testClassfilePath = Seq.from(testArgs.testCp),
              args = testArgs.arguments,
              startingTestClass = startingTestClass,
              testClassQueueFolder = testClassQueueFolder,
              claimFolder = claimFolder,
              cl = classLoader,
              testReporter = TestReporter(testArgs.logLevel),
              resultPath = testArgs.resultPath
            )
        }

        // Clear interrupted state in case some badly-behaved test suite
        // dirtied the thread-interrupted flag and forgot to clean up. Otherwise,
        // that flag causes writing the results to disk to fail
        Thread.interrupted()
        os.write(testArgs.outputPath, upickle.stream(result))
      }
    } catch {
      case e: Throwable =>
        println(e)
        e.printStackTrace()
    }
    // Tests are over, kill the JVM whether or not anyone's threads are still running
    // Always return 0, even if tests fail. The caller can pick up the detailed test
    // results from the outputPath
    System.exit(0)
  }

}
