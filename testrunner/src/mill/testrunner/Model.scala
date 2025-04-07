package mill.testrunner

import mill.api.JsonFormatters._
import mill.api.internal
import mill.runner.api.TestReporter

@internal case class TestArgs(
    framework: String,
    classpath: Seq[os.Path],
    arguments: Seq[String],
    sysProps: Map[String, String],
    outputPath: os.Path,
    resultPath: os.Path,
    colored: Boolean,
    testCp: Seq[os.Path],
    // globSelectors indicates the strategy for testrunner to find and run test classes
    // can be either:
    // - Left(selectors: Seq[String]): - list of glob selectors, testrunner is given a list of glob selectors to run directly
    // - Right((selectorFolder: os.Path, baseFolder: os.Path)): - a pair of paths, testrunner will try to claim test glob from selectorFolder
    // and move it actomatically in to baseFolder and run it from there.
    globSelectors: Either[Seq[String], (Option[String], os.Path, os.Path)],
    logLevel: TestReporter.LogLevel
)

@internal object TestArgs {
  implicit lazy val logLevelRW: upickle.default.ReadWriter[TestReporter.LogLevel] =
    implicitly[upickle.default.ReadWriter[String]].bimap(
      _.asString,
      TestReporter.LogLevel.fromString(_)
    )
  implicit def resultRW: upickle.default.ReadWriter[TestArgs] = upickle.default.macroRW
}

@internal case class TestResult(
    fullyQualifiedName: String,
    selector: String,
    duration: Long,
    status: String,
    exceptionName: Option[String] = None,
    exceptionMsg: Option[String] = None,
    exceptionTrace: Option[Seq[StackTraceElement]] = None
)

@internal object TestResult {
  implicit def resultRW: upickle.default.ReadWriter[TestResult] = upickle.default.macroRW
}
