package mill.util

import ammonite.main.Router.Overrides
import ammonite.ops.Path
import mill.define.Task.Module
import mill.define.{BasePath, Caller, Target, Task}
import mill.eval.Result
import utest.assert
import utest.framework.TestPath

import scala.collection.mutable

object TestUtil {
  class BaseModule(implicit millModuleEnclosing0: sourcecode.Enclosing,
                   millModuleLine0: sourcecode.Line,
                   millName0: sourcecode.Name)
    extends Module()(
      Module.Ctx.make(
        implicitly,
        implicitly,
        implicitly,
        BasePath(ammonite.ops.pwd / millModuleEnclosing0.value)
      )
    )
  object test{

    def anon(inputs: Task[Int]*) = new Test(inputs)
    def apply(inputs: Task[Int]*)
            (implicit enclosing0: sourcecode.Enclosing,
             owner0: Caller[mill.Module],
             name0: sourcecode.Name)= {
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
                  (implicit enclosing0: sourcecode.Enclosing,
                   lineNum0: sourcecode.Line,
                   owner0: Caller[mill.Module],
                   name0: sourcecode.Name,
                   o: Overrides)
    extends Test(inputs) with Target[Int]{
    val overrides = o.value
    val enclosing = enclosing0.value
    val lineNum = lineNum0.value
    val owner = owner0.value
    val name = name0.value
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
