package mill.api

import scala.annotation.{StaticAnnotation, compileTimeOnly}
import scala.language.implicitConversions
import os.Path

/**
 * Provides access to various resources in the context of a currently execution Target.
 */
object Ctx {
  @compileTimeOnly("Target.ctx() / T.ctx() / T.* APIs can only be used with a T{...} block")
  @ImplicitStub
  implicit def taskCtx: Ctx = ???

  /** Access to the targets destination path. */
  trait Dest {
    def dest: os.Path
  }
  object Dest {
    implicit def pathToCtx(path: os.Path): Dest = new Dest { def dest = path }
  }

  /** Access to the targets [[Logger]] instance. */
  trait Log {
    def log: Logger
  }
  object Log {
    implicit def logToCtx(l: Logger): Log = new Log { def log = l }
  }

  /**
   * Access to some internal storage dir used by underlying ammonite.
   * You should not need this in a buildscript.
   */
  trait Home {
    def home: os.Path
  }

  /** Access to the current system environment settings. */
  trait Env {
    def env: Map[String, String]
  }

  trait Args {
    def args: IndexedSeq[_]
  }

  /**
   * Access to the selected parallel job count (`mill --jobs`).
   */
  trait Jobs {
    def jobs: Int
  }

  /** Access to the project root (aka workspace) directory. */
  trait Workspace {
    def workspace: os.Path
  }

  def defaultHome = os.home / ".mill" / "ammonite"

  /**
   * Marker annotation.
   */
  class ImplicitStub extends StaticAnnotation
}

class Ctx(
    val args: IndexedSeq[_],
    dest0: () => os.Path,
    val log: Logger,
    val home: os.Path,
    val env: Map[String, String],
    val reporter: Int => Option[CompileProblemReporter],
    val testReporter: TestReporter,
    val workspace: os.Path
) extends Ctx.Dest
    with Ctx.Log
    with Ctx.Args
    with Ctx.Home
    with Ctx.Env
    with Ctx.Workspace {

  def dest: Path = dest0()
  def length: Int = args.length
  def apply[T](index: Int): T = {
    if (index >= 0 && index < args.length) args(index).asInstanceOf[T]
    else throw new IndexOutOfBoundsException(s"Index $index outside of range 0 - ${args.length}")
  }
}
