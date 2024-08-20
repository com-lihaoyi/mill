package mill.testkit

import mill.api.DummyInputStream
import mill.eval.Evaluator
import utest.framework.TestPath

import java.io.{InputStream, PrintStream}

object TestEvaluator {
  def static(module: => MillTestKit.BaseModule)(implicit
      fullName: sourcecode.FullName
  ): TestEvaluator = {
    new TestEvaluator(module)(fullName, TestPath(Nil))
  }
}

/**
 * @param module The module under test
 * @param failFast failFast mode enabled
 * @param threads explicitly used nr. of parallel threads
 */
class TestEvaluator(
    module: MillTestKit.BaseModule,
    failFast: Boolean = false,
    threads: Option[Int] = Some(1),
    outStream: PrintStream = System.out,
    errStream: PrintStream = System.err,
    inStream: InputStream = DummyInputStream,
    debugEnabled: Boolean = false,
    extraPathEnd: Seq[String] = Seq.empty,
    env: Map[String, String] = Evaluator.defaultEnv
)(implicit fullName: sourcecode.FullName, tp: TestPath) extends MillTestKit.TestEvaluator(
      module,
      tp.value,
      failFast,
      threads,
      outStream,
      errStream,
      inStream,
      debugEnabled,
      extraPathEnd,
      env
    )
