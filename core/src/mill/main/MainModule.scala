package mill.main

trait MainModule extends mill.Module{
  implicit def millDiscover: mill.define.Discover[_]
  implicit def millScoptTargetReads[T] = new mill.main.TargetScopt[T]()
  implicit def millScoptEvaluatorReads[T] = new mill.main.EvaluatorScopt[T]()
  def resolve(targets: mill.main.MagicScopt.Tasks[Any]*) = mill.T.command{
    targets.flatMap(_.items).foreach(println)
  }
  def all(evaluator: mill.eval.Evaluator[Any],
          targets: mill.main.MagicScopt.Tasks[Any]*) = mill.T.command{
    val (watched, res) = mill.main.RunScript.evaluate(
      evaluator,
      mill.util.Strict.Agg.from(targets.flatMap(_.items))
    )
  }
  def show(evaluator: mill.eval.Evaluator[Any],
           targets: mill.main.MagicScopt.Tasks[Any]*) = mill.T.command{
    val (watched, res) = mill.main.RunScript.evaluate(
      evaluator,
      mill.util.Strict.Agg.from(targets.flatMap(_.items))
    )
    for(json <- res.right.get.flatMap(_._2)){
      println(json)
    }
  }
}
