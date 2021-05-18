package mill.scalalib

import scala.util.Success

import mill.scalalib.TestRunner.TestArgs
import org.scalacheck.Prop.forAll
import utest._

object TestRunnerTests extends TestSuite {
  override def tests: Tests = Tests {
    test("TestArgs args serialization") {
      forAll {
        (
            frameworks: Seq[String],
            classpath: Seq[String],
            arguments: Seq[String],
            sysProps: Map[String, String],
            outputPath: String,
            colored: Boolean,
            testCp: String,
            homeStr: String
        ) =>
          val testArgs = TestArgs(
            frameworks,
            classpath,
            arguments,
            sysProps,
            outputPath,
            colored,
            testCp,
            homeStr
          )
          TestArgs.parseArgs(testArgs.toArgsSeq.toArray) == Success(testArgs)
      }.check
    }
  }
}
