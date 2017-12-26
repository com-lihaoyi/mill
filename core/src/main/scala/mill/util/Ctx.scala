package mill.util

import ammonite.ops.Path
import mill.define.Applicative.ImplicitStub
import mill.util.Ctx.{ArgCtx, DestCtx, LoaderCtx, LogCtx, MappingCtx}

import scala.annotation.compileTimeOnly
import scala.language.implicitConversions

object Ctx{
  @compileTimeOnly("Target.ctx() can only be used with a T{...} block")
  @ImplicitStub
  implicit def taskCtx: Ctx = ???

  object DestCtx {
    implicit def pathToCtx(path: Path): DestCtx =
      new DestCtx { def dest: Path = path }
  }
  trait DestCtx{
    def dest: Path
  }
  trait LogCtx{
    def log: Logger
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
  trait MappingCtx{
    def mapping: mill.discover.Discovered.Mapping[_]
  }
}
class Ctx(val args: IndexedSeq[_],
          val dest: Path,
          val log: Logger,
          workerCtx0: Ctx.LoaderCtx,
          val mapping: mill.discover.Discovered.Mapping[_])
  extends DestCtx
  with LogCtx
  with ArgCtx
  with LoaderCtx
  with MappingCtx{

  def load[T](x: Ctx.Loader[T]): T = workerCtx0.load(x)
  def length = args.length
  def apply[T](index: Int): T = {
    if (index >= 0 && index < args.length) args(index).asInstanceOf[T]
    else throw new IndexOutOfBoundsException(s"Index $index outside of range 0 - ${args.length}")
  }
}
