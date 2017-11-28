package mill.eval

import ammonite.ops.ImplicitWd._
import ammonite.ops._
import mill.define.{Target, Task}
import mill.discover.Discovered
import mill.modules.Jvm.jarUp
import mill.util.OSet

class JavaCompileUtils(path: Path, mapping: Map[mill.define.Target[_],mill.discover.Mirror.LabelledTarget[_]]) {
  def compileAll(dest: Path, sources: Seq[PathRef]) = {
    mkdir(dest)
    import ammonite.ops._
    %("javac", sources.map(_.path.toString()), "-d", dest)(wd = dest)
    PathRef(dest)
  }

  def eval[T](t: Task[T]): Either[Result.Failing, (T, Int)] = {
    val evaluator = new Evaluator(path, mapping, _ => ())
    val evaluated = evaluator.evaluate(OSet(t))

    if (evaluated.failing.keyCount == 0){
      Right(Tuple2(
        evaluated.rawValues(0).asInstanceOf[Result.Success[T]].value,
        evaluated.evaluated.collect{
          case t: Target[_] if mapping.contains(t) => t
          case t: mill.define.Command[_] => t
        }.size
      ))
    }else{
      Left(evaluated.failing.lookupKey(evaluated.failing.keys().next).items.next())
    }
  }

  def check(targets: OSet[Task[_]], expected: OSet[Task[_]]) = {
    val evaluator = new Evaluator(path, mapping, _ => ())

    val evaluated = evaluator.evaluate(targets)
      .evaluated
      .flatMap(_.asTarget)
      .filter(mapping.contains)
    assert(evaluated == expected)
  }
}
