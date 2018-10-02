package mill.main
import mill.eval.Evaluator

case class Tasks[T](value: Seq[mill.define.NamedTask[T]])

object Tasks{

  class Scopt[T]() extends scopt.Read[Tasks[T]] {
    def arity = 1

    def reads = s => {
      RunScript.resolveTasks(
        mill.main.ResolveTasks,
        Evaluator.currentEvaluator.get,
        Seq(s),
        multiSelect = false
      ) match{
        case Left(err) => throw new Exception(err)
        case Right(tasks) => Tasks(tasks).asInstanceOf[Tasks[T]]
      }
    }
  }
}

class EvaluatorScopt[T]()
  extends scopt.Read[mill.eval.Evaluator]{
  def arity = 0
  def reads = s => try{
    Evaluator.currentEvaluator.get.asInstanceOf[mill.eval.Evaluator]
  }
}
