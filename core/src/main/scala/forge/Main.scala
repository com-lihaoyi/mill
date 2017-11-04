package forge

import ammonite.ops._
import ammonite.util.{Name, Res}
import forge.util.OSet


object Main {
  def main(args: Array[String]): Unit = {

    ammonite.Main().instantiateInterpreter() match{
      case Right(interp) =>
        val result = ammonite.main.Scripts.runScript(pwd, Path(args(0), pwd), interp, Nil)

        val (obj, discovered) = result.asInstanceOf[Res.Success[(Any, forge.Discovered[Any])]].s
        val mapping = Discovered.mapping(obj)(discovered)
        val workspacePath = pwd / 'target / 'javac
        val evaluator = new Evaluator(workspacePath, mapping)
        val evaluated = evaluator.evaluate(OSet.from(mapping.keys)).evaluated.filter(mapping.contains)
        (result, interp.watchedFiles)
      case Left(problems) => problems
    }
  }

}
