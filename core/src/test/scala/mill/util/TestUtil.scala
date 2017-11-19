package mill.util

import mill.define.{Target, Task}
import mill.eval.Result
import utest.assert

import scala.collection.mutable

object TestUtil {
  def test(inputs: Task[Int]*) = {
    new Test(inputs, pure = inputs.nonEmpty)
  }

  /**
    * A dummy target that takes any number of inputs, and whose output can be
    * controlled externally, so you can construct arbitrary dataflow graphs and
    * test how changes propagate.
    */
  class Test(override val inputs: Seq[Task[Int]],
             val pure: Boolean) extends Target[Int]{
    var counter = 0
    var failure = Option.empty[String]
    var exception = Option.empty[Throwable]
    override def evaluate(args: Args) = {
      failure.map(Result.Failure) orElse
      exception.map(Result.Exception) getOrElse
      Result.Success(counter + args.args.map(_.asInstanceOf[Int]).sum)
    }

    override def sideHash = counter + failure.hashCode() + exception.hashCode()
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
