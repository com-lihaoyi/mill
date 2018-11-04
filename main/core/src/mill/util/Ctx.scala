package mill.util

import mill.define.Applicative.ImplicitStub

import scala.annotation.compileTimeOnly
import scala.language.implicitConversions

object Ctx{
  @compileTimeOnly("Target.ctx() can only be used with a T{...} block")
  @ImplicitStub
  implicit def taskCtx: Ctx = ???

  object Dest {
    implicit def pathToCtx(path: os.Path): Dest = new Dest { def dest = path }
  }
  trait Dest{
    def dest: os.Path
  }
  trait Log{
    def log: Logger
  }
  trait Home{
    def home: os.Path
  }
  trait Env{
    def env: Map[String, String]
  }
  object Log{
    implicit def logToCtx(l: Logger): Log = new Log { def log = l }
  }
  trait Args{
    def args: IndexedSeq[_]
  }

  def defaultHome = ammonite.ops.home / ".mill" / "ammonite"

}
class Ctx(val args: IndexedSeq[_],
          dest0: () => os.Path,
          val log: Logger,
          val home: os.Path,
          val env : Map[String, String])
  extends Ctx.Dest
  with Ctx.Log
  with Ctx.Args
  with Ctx.Home
  with Ctx.Env {

  def dest = dest0()
  def length = args.length
  def apply[T](index: Int): T = {
    if (index >= 0 && index < args.length) args(index).asInstanceOf[T]
    else throw new IndexOutOfBoundsException(s"Index $index outside of range 0 - ${args.length}")
  }
}
