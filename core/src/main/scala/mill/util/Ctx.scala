package mill.util

import ammonite.ops.Path
import mill.define.Applicative.ImplicitStub
import mill.util.Ctx.{ArgCtx, BaseCtx, DestCtx, LoaderCtx, LogCtx}

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
  trait LoaderCtx{
    def load[T](x: Loader[T]): T
  }
  trait Loader[T]{
    def make(): T
  }
}
class Ctx(val args: IndexedSeq[_],
          _dest: Path,
          val base: Path,
          val log: Logger,
          workerCtx0: Ctx.LoaderCtx)
  extends DestCtx
  with LogCtx
  with ArgCtx
  with LoaderCtx
  with BaseCtx{

  override def dest: Path = _dest

  def load[T](x: Ctx.Loader[T]): T = workerCtx0.load(x)
  def length = args.length
  def apply[T](index: Int): T = {
    if (index >= 0 && index < args.length) args(index).asInstanceOf[T]
    else throw new IndexOutOfBoundsException(s"Index $index outside of range 0 - ${args.length}")
  }
}
