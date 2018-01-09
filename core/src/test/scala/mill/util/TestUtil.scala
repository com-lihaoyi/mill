package mill.util

import ammonite.main.Router.Overrides
import mill.define._
import mill.eval.Result
import utest.assert

import scala.collection.mutable

object TestUtil {
  class BaseModule(implicit millModuleEnclosing0: sourcecode.Enclosing,
                   millModuleLine0: sourcecode.Line,
                   millName0: sourcecode.Name,
                   overrides: Overrides)
    extends Module()(
      Module.Ctx.make(
        implicitly,
        implicitly,
        implicitly,
        BasePath(ammonite.ops.pwd / millModuleEnclosing0.value),
        Segments(),
        implicitly
      )
    )
  object test{

    def anon(inputs: Task[Int]*) = new Test(inputs)
    def apply(inputs: Task[Int]*)
            (implicit ctx: Module.Ctx)= {
      new TestTarget(inputs, pure = inputs.nonEmpty)
    }
  }

  class Test(val inputs: Seq[Task[Int]]) extends Task[Int]{
    var counter = 0
    var failure = Option.empty[String]
    var exception = Option.empty[Throwable]
    override def evaluate(args: Ctx) = {
      failure.map(Result.Failure) orElse
        exception.map(Result.Exception(_, Nil)) getOrElse
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
                  (implicit ctx0: Module.Ctx)
    extends Test(inputs) with Target[Int]{
    val ctx = ctx0.copy(segments0 = ctx0.segments0 ++ Seq(ctx0.segment))
    val readWrite = upickle.default.IntRW


  }
  def checkTopological(targets: OSet[Task[_]]) = {
    val seen = mutable.Set.empty[Task[_]]
    for(t <- targets.indexed.reverseIterator){
      seen.add(t)
      for(upstream <- t.inputs){
        assert(!seen(upstream))
      }
    }
  }
}
