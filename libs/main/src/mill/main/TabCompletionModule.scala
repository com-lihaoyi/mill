package mill.main

import mill.*
import mill.api.SelectMode
import mill.define.{Discover, Evaluator, ExternalModule}

import mainargs.arg

object TabCompletionModule extends ExternalModule {

  lazy val millDiscover = Discover[this.type]

  def tabComplete(ev: Evaluator,
                  @arg(positional = true) index: Int,
                  args: mainargs.Leftover[String]) = Task.Command(exclusive = true){
    val currentToken = args.value(index)
    val deSlashed = currentToken.replace("\\", "")
    val trimmed = deSlashed.take(
      deSlashed.lastIndexWhere(c => !c.isLetterOrDigit && !"-_,".contains(c)) + 1
    )
    val query = trimmed.lastOption match{
      case None => "_"
      case Some('.') => trimmed + "_"
      case Some('[') => trimmed + "__]"
      case Some(',') => trimmed + "__]"
      case Some(']') => trimmed + "._"
    }

    ev.resolveSegments(Seq(query), SelectMode.Multi).map{ res =>
      res.map(_.render).filter(_.startsWith(deSlashed)).foreach(println)
    }
  }
}
