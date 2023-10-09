package mill.util

import java.io.{InputStream, PrintStream}
import mill.eval.Evaluator
import utest.framework.TestPath

import mill.api.DummyInputStream

object TestEvaluator {
  def static(module: => TestUtil.BaseModule)(implicit
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
    module: TestUtil.BaseModule,
    failFast: Boolean = false,
    threads: Option[Int] = Some(1),
    outStream: PrintStream = System.out,
    errStream: PrintStream = System.err,
    inStream: InputStream = DummyInputStream,
    debugEnabled: Boolean = false,
    extraPathEnd: Seq[String] = Seq.empty,
    env: Map[String, String] = Evaluator.defaultEnv
)(implicit fullName: sourcecode.FullName, tp: TestPath) extends TestUtil.TestEvaluator(
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
