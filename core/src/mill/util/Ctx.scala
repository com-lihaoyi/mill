package mill.util

import ammonite.ops.Path
import mill.define.Applicative.ImplicitStub
import mill.util.Ctx.{ArgCtx, BaseCtx, DestCtx, LogCtx}

import scala.annotation.compileTimeOnly
import scala.language.implicitConversions

object Ctx{
  @compileTimeOnly("Target.ctx() can only be used with a T{...} block")
  @ImplicitStub
  implicit def taskCtx: Ctx = ???

  object DestCtx {
    implicit def pathToCtx(path: Path): DestCtx = new DestCtx { def dest = path }
  }
  trait DestCtx{
    def dest: Path
  }
  trait BaseCtx{
    def base: Path
  }
  object BaseCtx {
    implicit def pathToCtx(path: Path): BaseCtx = new BaseCtx { def base = path }
  }
  trait LogCtx{
    def log: Logger
  }
  object LogCtx{
    implicit def logToCtx(l: Logger): LogCtx = new LogCtx { def log = l }
  }
  trait ArgCtx{
    def args: IndexedSeq[_]
  }

}
class Ctx(val args: IndexedSeq[_],
          dest0: () => Path,
          val base: Path,
          val log: Logger)
  extends DestCtx
  with LogCtx
  with ArgCtx
  with BaseCtx{

  def dest = dest0()
  def length = args.length
  def apply[T](index: Int): T = {
    if (index >= 0 && index < args.length) args(index).asInstanceOf[T]
    else throw new IndexOutOfBoundsException(s"Index $index outside of range 0 - ${args.length}")
  }
}
