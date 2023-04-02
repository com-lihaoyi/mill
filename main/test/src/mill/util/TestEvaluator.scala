package mill.util

import mill.testkit.MillTestKit
import java.io.{InputStream, PrintStream}
import mill.define.{Input, CachedTarget, Task}
import mill.api.Result.OuterStack
import mill.eval.Evaluator
import mill.api.Strict.Agg
import utest.assert
import utest.framework.TestPath

import language.experimental.macros
import mill.api.{DummyInputStream, Result}
object TestEvaluator {
  def static(module: => TestUtil.BaseModule)(implicit fullName: sourcecode.FullName) = {
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
      inStream,
      debugEnabled,
      extraPathEnd,
      env
    )
