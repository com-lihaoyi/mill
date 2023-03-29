package mill.scalalib

import scala.util.control.NonFatal

import mill.T
import mill.define.{Command, Discover, ExternalModule}
import mill.eval.Evaluator
import mill.api.Result

object GenIdea extends ExternalModule {

  def idea(ev: Evaluator): Command[Unit] = T.command {
    try {
      Result.Success(mill.scalalib.GenIdeaImpl(
        evaluator = ev,
        ctx = implicitly,
        rootModule = ev.rootModule,
        discover = ev.rootModule.millDiscover
      ).run())
    } catch {
      case GenIdeaImpl.GenIdeaException(m) => Result.Failure(m)
      case NonFatal(e) =>
        Result.Exception(e, new Result.OuterStack(new java.lang.Exception().getStackTrace))
    }
  }

  import mill.main.TokenReaders._
  override lazy val millDiscover = Discover[this.type]
}
