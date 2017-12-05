package mill.util

import java.io.PrintStream

import ammonite.ops.Path
import mill.define.Applicative.ImplicitStub
import mill.util.Ctx.{ArgCtx, DestCtx, LogCtx}

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
}
class Ctx(val args: IndexedSeq[_],
          val dest: Path,
          val log: Logger) extends DestCtx with LogCtx with ArgCtx{
  def length = args.length
  def apply[T](index: Int): T = {
    if (index >= 0 && index < args.length) args(index).asInstanceOf[T]
    else throw new IndexOutOfBoundsException(s"Index $index outside of range 0 - ${args.length}")
  }
}
