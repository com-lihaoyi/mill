package mill.main
import mill.define.ExternalModule
import mill.main.ParseArgs

object MagicScopt{
  // This needs to be a ThreadLocal because we need to pass it into the body of
  // the TargetScopt#read call, which does not accept additional parameters.
  // Until we migrate our CLI parsing off of Scopt (so we can pass the BaseModule
  // in directly) we are forced to pass it in via a ThreadLocal
  val currentEvaluator = new ThreadLocal[mill.eval.Evaluator[_]]

  case class Tasks[T](items: Seq[mill.define.NamedTask[T]])
}
class EvaluatorScopt[T]()
  extends scopt.Read[mill.eval.Evaluator[T]]{
  def arity = 0
  def reads = s => try{
    MagicScopt.currentEvaluator.get.asInstanceOf[mill.eval.Evaluator[T]]
  }
}
class TargetScopt[T]()
  extends scopt.Read[MagicScopt.Tasks[T]]{
  def arity = 0
  def reads = s => {
    val rootModule = MagicScopt.currentEvaluator.get.rootModule
    val d = rootModule.millDiscover
    val (expanded, leftover) = ParseArgs(Seq(s)).fold(e => throw new Exception(e), identity)
    val resolved = expanded.map{
      case (Some(scoping), segments) =>
        val moduleCls = rootModule.getClass.getClassLoader.loadClass(scoping.render + "$")
        val externalRootModule = moduleCls.getField("MODULE$").get(moduleCls).asInstanceOf[ExternalModule]
        val crossSelectors = segments.value.map {
          case mill.define.Segment.Cross(x) => x.toList.map(_.toString)
          case _ => Nil
        }
        mill.main.Resolve.resolve(segments.value.toList, externalRootModule, d, leftover, crossSelectors.toList, Nil)
      case (None, segments) =>
        val crossSelectors = segments.value.map {
          case mill.define.Segment.Cross(x) => x.toList.map(_.toString)
          case _ => Nil
        }
        mill.main.Resolve.resolve(segments.value.toList, rootModule, d, leftover, crossSelectors.toList, Nil)
    }
    mill.util.EitherOps.sequence(resolved) match{
      case Left(s) => throw new Exception(s)
      case Right(ts) => MagicScopt.Tasks(ts.flatten).asInstanceOf[MagicScopt.Tasks[T]]
    }
  }
}