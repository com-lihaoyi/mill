package forge

import ammonite.ops._
import ammonite.util.{Name, Res}
import forge.define.Target
import forge.discover.{Discovered, NestedEntry}
import forge.eval.Evaluator
import forge.util.OSet
import play.api.libs.json.Format


object Main {
  def main(args: Array[String]): Unit = {

    val List(buildFile, selector0, rest @_*) = args.toList
    pprint.log((buildFile, selector0, rest))
    val selector = selector0.split('.').toList
    ammonite.Main().instantiateInterpreter() match{
      case Left(problems) => pprint.log(problems)
      case Right(interp) =>
        val result = ammonite.main.Scripts.runScript(pwd, Path(buildFile, pwd), interp, Nil)

        if (!result.isSuccess) println(result)
        else{

          val (obj, discovered) = result.asInstanceOf[Res.Success[(Any, Discovered[Any])]].s
          val mapping = Discovered.mapping(obj)(discovered)
          val workspacePath = pwd / 'out
          val evaluator = new Evaluator(workspacePath, mapping)
          val mainRoutes = discovered.mains.map(x => (x.path :+ x.entryPoint.name, Left(x)))
          val targetRoutes = discovered.targets.map(x => x._1 -> Right(x))
          val allRoutes = (mainRoutes ++ targetRoutes).toMap[
            Seq[String],
            Either[NestedEntry[Any, _], (Seq[String], Format[_], Any => Target[_])]
          ]
          allRoutes.get(selector) match{
            case Some(Left(nestedEntryPoint)) =>
              nestedEntryPoint.invoke(
                obj,
                ammonite.main.Scripts.groupArgs(rest.toList)
              ) match{
                case error: forge.discover.Router.Result.Error =>
                  println("Failed to evaluate main method: " + error)
                case forge.discover.Router.Result.Success(target) =>
                  println("Found target! " + target)
                  val evaluated = evaluator.evaluate(OSet(target))
                  pprint.log(evaluated)
              }

            case None => println("Unknown selector: " + selector)
            case Some(Right((_, _, targetFunc))) =>
              val target = targetFunc(obj)
              val evaluated = evaluator.evaluate(OSet(target))
              pprint.log(evaluated)
          }
        }
    }
  }

}
