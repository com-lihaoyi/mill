package mill.main

import mill.define._
import mill.define.TaskModule
import ammonite.util.Res
import mill.util.Router.EntryPoint
import mill.util.{Router, Scripts}

import scala.reflect.ClassTag

object ResolveMetadata extends Resolve[String]{
  def singleModuleMeta(obj: Module, discover: Discover[_], isRootModule: Boolean) = {
    val modules = obj.millModuleDirectChildren.map(_.toString)
    val targets =
      obj
        .millInternal
        .reflect[Target[_]]
        .map(_.toString)
    val commands = for{
      (cls, entryPoints) <- discover.value
      if cls.isAssignableFrom(obj.getClass)
      ep <- entryPoints
    } yield {
      if (isRootModule) ep._2.name
      else obj + "." + ep._2.name
    }

    modules ++ targets ++ commands
  }
  def endResolve(obj: Module,
                 revSelectorsSoFar: List[Segment],
                 last: String,
                 discover: Discover[_],
                 rest: Seq[String]): Either[String, List[String]] = {

    val direct = singleModuleMeta(obj, discover, revSelectorsSoFar.isEmpty)
    if (last == "__") {
      Right(direct.toList ++ obj.millInternal.modules.flatMap(singleModuleMeta(_, discover, false)))
    } else if (last == "_") Right(direct.toList)
    else direct.find(_.split('.').last == last) match{
      case None =>
        Left(
          "Unable to resolve " +
            Segments((Segment.Label(last) :: revSelectorsSoFar).reverse: _*).render
        )
      case Some(s) => Right(List(s))
    }
  }
}
object Resolve extends Resolve[NamedTask[Any]]{
  def endResolve(obj: Module,
                 revSelectorsSoFar: List[Segment],
                 last: String,
                 discover: Discover[_],
                 rest: Seq[String]) = {
    val target =
      obj
        .millInternal
        .reflect[Target[_]]
        .find(_.label == last)
        .map(Right(_))

    def shimArgsig[T](a: Router.ArgSig[T, _]) = {
      ammonite.main.Router.ArgSig[T](
        a.name,
        a.typeString,
        a.doc,
        a.default
      )
    }

    def invokeCommand(target: Module, name: String) = for {
      (cls, entryPoints) <- discover.value
      if cls.isAssignableFrom(target.getClass)
      ep <- entryPoints
      if ep._2.name == name
    } yield Scripts.runMainMethod(
      target,
      ep._2.asInstanceOf[EntryPoint[Module]],
      ammonite.main.Scripts.groupArgs(rest.toList)
    ) match {
      case Res.Success(v: Command[_]) => Right(v)
      case Res.Failure(msg) => Left(msg)
      case Res.Exception(ex, msg) =>
        val sw = new java.io.StringWriter()
        ex.printStackTrace(new java.io.PrintWriter(sw))
        val prefix = if (msg.nonEmpty) msg + "\n" else msg
        Left(prefix + sw.toString)

    }

    val runDefault = for {
      child <- obj.millInternal.reflectNestedObjects[Module]
      if child.millOuterCtx.segment == Segment.Label(last)
      res <- child match {
        case taskMod: TaskModule => Some(invokeCommand(child, taskMod.defaultCommandName()).headOption)
        case _ => None
      }
    } yield res

    val command = invokeCommand(obj, last).headOption

    command orElse target orElse runDefault.flatten.headOption match {
      case None => Left("Cannot resolve " +
        Segments((Segment.Label(last) :: revSelectorsSoFar).reverse: _*).render
      )
      // Contents of `either` *must* be a `Task`, because we only select
      // methods returning `Task` in the discovery process
      case Some(either) => either.right.map(Seq(_))
    }
  }

}
abstract class Resolve[R: ClassTag] {
  def endResolve(obj: Module,
                 revSelectorsSoFar: List[Segment],
                 last: String,
                 discover: Discover[_],
                 rest: Seq[String]): Either[String, Seq[R]]

  def resolve(remainingSelector: List[Segment],
              obj: mill.Module,
              discover: Discover[_],
              rest: Seq[String],
              remainingCrossSelectors: List[List[String]],
              revSelectorsSoFar: List[Segment]): Either[String, Seq[R]] = {

    remainingSelector match{
      case Segment.Cross(_) :: Nil => Left("Selector cannot start with a [cross] segment")
      case Segment.Label(last) :: Nil =>
        endResolve(obj, revSelectorsSoFar, last, discover, rest)

      case head :: tail =>
        val newRevSelectorsSoFar = head :: revSelectorsSoFar

        def resolveFailureMsg = Left(
          "Cannot resolve " + Segments(newRevSelectorsSoFar.reverse:_*).render
        )
        def recurse(searchModules: Seq[Module]) = {
          val matching = searchModules
            .map(resolve(tail, _, discover, rest, remainingCrossSelectors, newRevSelectorsSoFar))

          matching match{
            case Seq(Left(err)) => Left(err)
            case items =>
              items.collect{case Right(v) => v} match{
                case Nil => resolveFailureMsg
                case values => Right(values.flatten)
              }
          }
        }
        head match{
          case Segment.Label(singleLabel) =>
            recurse(
              if (singleLabel == "__") obj.millInternal.modules
              else if (singleLabel == "_") obj.millModuleDirectChildren.toSeq
              else{
                obj.millInternal.reflectNestedObjects[mill.Module]
                  .find(_.millOuterCtx.segment == Segment.Label(singleLabel))
                  .toSeq
              }
            )
          case Segment.Cross(cross) =>
            obj match{
              case c: Cross[Module] =>
                recurse(
                  if(cross == Seq("__")) for ((k, v) <- c.items) yield v
                  else if (cross.contains("_")){
                      for {
                        (k, v) <- c.items
                        if k.length == cross.length
                        if k.zip(cross).forall { case (l, r) => l == r || r == "_" }
                      } yield v
                  }else c.itemMap.get(cross.toList).toSeq
                )
              case _ => resolveFailureMsg
            }
        }

      case Nil => Left("Selector cannot be empty")
    }
  }
}
