package mill.testrunner

import mill.api.JsonFormatters._
import mill.api.internal

@internal case class TestArgs(
    framework: String,
    classpath: Seq[os.Path],
    arguments: Seq[String],
    sysProps: Map[String, String],
    outputPath: os.Path,
    colored: Boolean,
    testCp: os.Path,
    home: os.Path,
    globSelectors: Seq[String]
)

@internal object TestArgs {
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
