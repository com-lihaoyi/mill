package mill

import ammonite.ops._
import ammonite.util.{Name, Res}
import mill.define.Task
import mill.discover._
import mill.eval.Evaluator
import mill.util.OSet
import play.api.libs.json.Format


object Main {
  def apply[T: Discovered](args: Seq[String], obj: T, watch: Path => Unit) = {
    val startTime = System.currentTimeMillis()
    val Seq(selectorString, rest @_*) = args
    val selector = selectorString.split('.')
    val discovered = implicitly[Discovered[T]]
    val consistencyErrors = Discovered.consistencyCheck(obj, discovered)
    if (consistencyErrors.nonEmpty) println("Failed Discovered.consistencyCheck: " + consistencyErrors)
    else {
      val mapping = Discovered.mapping(obj)(discovered)
      val workspacePath = pwd / 'out

      val mainRoutes = discovered.mains.map(x => (x.path :+ x.entryPoint.name, x: Info[T, _]))
      val targetRoutes = discovered.targets.map(x => (x.path, x: Info[T, _]))
      val routeList: Seq[(Seq[String], Info[T, _])] = mainRoutes ++ targetRoutes
      val routeMap = routeList.toMap
      routeMap.get(selector) match{
        case Some(info) =>
          val target = getTarget(obj, info, rest.toList)
          val evaluator = new Evaluator(workspacePath, mapping)
          val evaluated = evaluator.evaluate(OSet(target))

          val delta = System.currentTimeMillis() - startTime
          println(fansi.Color.Blue("Finished in " + delta/1000.0 + "s"))
          evaluated.transitive.foreach{
            case t: define.Source => watch(t.handle.path)
            case _ => // do nothing
          }

        case None => println("Unknown selector: " + selector)
      }
    }
  }

  def getTarget[T](obj: T, info: Info[T, _], args: List[String]) = info match{
    case nestedEntryPoint: CommandInfo[T, _] =>
      nestedEntryPoint.invoke(
        obj,
        ammonite.main.Scripts.groupArgs(args)
      ) match{
        case error: Router.Result.Error =>
          throw new Exception("Failed to evaluate main method: " + error)
        case mill.discover.Router.Result.Success(target) => target
      }
    case labelled: LabelInfo[T, _] => labelled.run(obj)
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
