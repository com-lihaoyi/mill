package mill.util

import java.io.PrintStream

import ammonite.ops.Path
import mill.define.Applicative.ImplicitStub
import mill.define.Worker
import mill.util.Ctx.{ArgCtx, DestCtx, LogCtx, WorkerCtx}

import scala.annotation.compileTimeOnly

object Ctx{
  @compileTimeOnly("Target.ctx() can only be used with a T{...} block")
  @ImplicitStub
  implicit def taskCtx: Ctx = ???

  trait DestCtx{
    def dest: Path
  }
  trait LogCtx{
    def log: Logger
  }
  trait ArgCtx{
    def args: IndexedSeq[_]
  }
  trait WorkerCtx{
    def workerFor[T](x: mill.define.Worker[T]): T
  }
}
class Ctx(val args: IndexedSeq[_],
          val dest: Path,
          val log: Logger,
          workerCtx0: Ctx.WorkerCtx) extends DestCtx with LogCtx with ArgCtx with WorkerCtx{

  def workerFor[T](x: mill.define.Worker[T]): T = workerCtx0.workerFor(x)
  def length = args.length
  def apply[T](index: Int): T = {
    if (index >= 0 && index < args.length) args(index).asInstanceOf[T]
    else throw new IndexOutOfBoundsException(s"Index $index outside of range 0 - ${args.length}")
  }
}
