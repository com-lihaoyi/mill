package mill.util

import mill.util.Router.Overrides
import mill.define._
import mill.eval.Result
import mill.eval.Result.OuterStack
import utest.assert
import mill.util.Strict.Agg
import utest.framework.TestPath

import scala.collection.mutable

object TestUtil {
  def getOutPath()(implicit fullName: sourcecode.FullName,
                   tp: TestPath) = {
    os.pwd / 'target / 'workspace / (fullName.value.split('.') ++ tp.value)
  }
  def getOutPathStatic()(implicit fullName: sourcecode.FullName) = {
    os.pwd / 'target / 'workspace / fullName.value.split('.')
  }

  def getSrcPathStatic()(implicit fullName: sourcecode.FullName) = {
    os.pwd / 'target / 'worksources / fullName.value.split('.')
  }
  def getSrcPathBase() = {
    os.pwd / 'target / 'worksources
  }

  class BaseModule(implicit millModuleEnclosing0: sourcecode.Enclosing,
                   millModuleLine0: sourcecode.Line,
                   millName0: sourcecode.Name,
                   overrides: Overrides)
    extends mill.define.BaseModule(getSrcPathBase() / millModuleEnclosing0.value.split("\\.| |#"))(
      implicitly, implicitly, implicitly, implicitly, implicitly){
    lazy val millDiscover: Discover[this.type] = Discover[this.type]
  }

  object test{

    def anon(inputs: Task[Int]*) = new Test(inputs)
    def apply(inputs: Task[Int]*)
            (implicit ctx: mill.define.Ctx)= {
      new TestTarget(inputs, pure = inputs.nonEmpty)
    }
  }

  class Test(val inputs: Seq[Task[Int]]) extends Task[Int]{
    var counter = 0
    var failure = Option.empty[String]
    var exception = Option.empty[Throwable]
    override def evaluate(args: Ctx) = {
      failure.map(Result.Failure(_)) orElse
      exception.map(Result.Exception(_, new OuterStack(Nil))) getOrElse
      Result.Success(counter + args.args.map(_.asInstanceOf[Int]).sum)
    }
    override def sideHash = counter + failure.hashCode() + exception.hashCode()
  }
  /**
    * A dummy target that takes any number of inputs, and whose output can be
    * controlled externally, so you can construct arbitrary dataflow graphs and
    * test how changes propagate.
    */
  class TestTarget(inputs: Seq[Task[Int]],
                   val pure: Boolean)
                  (implicit ctx0: mill.define.Ctx)
    extends Test(inputs) with Target[Int]{
    val ctx = ctx0.copy(segments = ctx0.segments ++ Seq(ctx0.segment))
    val readWrite = upickle.default.readwriter[Int]


  }
  def checkTopological(targets: Agg[Task[_]]) = {
    val seen = mutable.Set.empty[Task[_]]
    for(t <- targets.indexed.reverseIterator){
      seen.add(t)
      for(upstream <- t.inputs){
        assert(!seen(upstream))
      }
    }
  }
  def disableInJava9OrAbove(f: => Any): Unit = {
    if (!ammonite.util.Util.java9OrAbove) {
      f
    }
  }
}
