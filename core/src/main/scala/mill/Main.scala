package mill

import ammonite.ops._
import ammonite.util.{Name, Res}
import mill.define.Task
import mill.discover.{CommandInfo, Discovered, Info, LabelInfo}
import mill.eval.Evaluator
import mill.util.OSet
import play.api.libs.json.Format


object Main {
  def apply[T: Discovered](args: Seq[String], obj: T, watch: Path => Unit) = {
    val Seq(selectorString, rest @_*) = args
    val selector = selectorString.split('.')
    val discovered = implicitly[Discovered[T]]
    val consistencyErrors = Discovered.consistencyCheck(obj, discovered)
    pprint.log(consistencyErrors)
    if (consistencyErrors.nonEmpty) println("Failed Discovered.consistencyCheck: " + consistencyErrors)
    else {
      val mapping = Discovered.mapping(obj)(discovered)
      val workspacePath = pwd / 'out
      val evaluator = new Evaluator(workspacePath, mapping)
      val mainRoutes = discovered.mains.map(x => (x.path :+ x.entryPoint.name, x: Info[T, _]))
      val targetRoutes = discovered.targets.map(x => (x.path, x: Info[T, _]))
      val routeList: Seq[(Seq[String], Info[T, _])] = mainRoutes ++ targetRoutes
      val allRoutes = routeList.toMap[Seq[String], Info[_, _]]
      allRoutes.get(selector) match{
        case Some(nestedEntryPoint: CommandInfo[T, _]) =>
          nestedEntryPoint.invoke(
            obj,
            ammonite.main.Scripts.groupArgs(rest.toList)
          ) match{
            case error: mill.discover.Router.Result.Error =>
              println("Failed to evaluate main method: " + error)
            case mill.discover.Router.Result.Success(target) =>
              println("Found target! " + target)
              val evaluated = evaluator.evaluate(OSet(target))

              evaluated.transitive.foreach{
                case t: define.Source =>
                  println("Watching " + t.handle.path)
                  watch(t.handle.path)
                case _ => // do nothing
              }
          }

        case Some(labelled: LabelInfo[T, _]) =>
          val target = labelled.run(obj)
          val evaluated = evaluator.evaluate(OSet(target))
          pprint.log(evaluated)
        case None => println("Unknown selector: " + selector)
      }
    }
  }

  def main(args: Array[String]): Unit = {

    val List(buildFile, rest @_*) = args.toList

    ammonite.Main().instantiateInterpreter() match{
      case Left(problems) => pprint.log(problems)
      case Right(interp) =>
        val result = ammonite.main.Scripts.runScript(pwd, Path(buildFile, pwd), interp, Nil)

        if (!result.isSuccess) println(result)
        else{
          val (obj, discovered) = result.asInstanceOf[Res.Success[(Any, Discovered[Any])]].s
          apply(rest, obj, _ => ())(discovered)

        }
    }
  }

}
