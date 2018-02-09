package mill.main

import mill.define._
import mill.define.TaskModule
import ammonite.util.Res
import mill.util.Router.EntryPoint
import mill.util.Scripts

object Resolve {
  def resolve[T, V](remainingSelector: List[Segment],
                    obj: mill.Module,
                    discover: Discover[_],
                    rest: Seq[String],
                    remainingCrossSelectors: List[List[String]],
                    revSelectorsSoFar: List[Segment]): Either[String, Seq[NamedTask[Any]]] = {

    remainingSelector match{
      case Segment.Cross(_) :: Nil => Left("Selector cannot start with a [cross] segment")
      case Segment.Label(last) :: Nil =>
        val target =
          obj
            .millInternal
            .reflect[Target[_]]
            .find(_.label == last)
            .map(Right(_))

        def shimArgsig[T](a: mill.util.Router.ArgSig[T, _]) = {
          ammonite.main.Router.ArgSig[T](
            a.name,
            a.typeString,
            a.doc,
            a.default
          )
        }
        def invokeCommand(target: mill.Module, name: String) = for{
          (cls, entryPoints) <- discover.value
          if cls.isAssignableFrom(target.getClass)
          ep <- entryPoints
          if ep._2.name == name
        } yield Scripts.runMainMethod(
          target,
          ep._2.asInstanceOf[EntryPoint[mill.Module]],
          ammonite.main.Scripts.groupArgs(rest.toList)
        ) match{
          case Res.Success(v: Command[_]) => Right(v)
          case Res.Failure(msg) => Left(msg)
          case Res.Exception(ex, msg) =>
            val sw = new java.io.StringWriter()
            ex.printStackTrace(new java.io.PrintWriter(sw))
            val prefix = if (msg.nonEmpty) msg + "\n" else msg
            Left(prefix + sw.toString)

        }

        val runDefault = for{
          child <- obj.millInternal.reflectNestedObjects[mill.Module]
          if child.millOuterCtx.segment == Segment.Label(last)
          res <- child match{
            case taskMod: TaskModule => Some(invokeCommand(child, taskMod.defaultCommandName()).headOption)
            case _ => None
          }
        } yield res

        val command = invokeCommand(obj, last).headOption

        command orElse target orElse runDefault.flatten.headOption match{
          case None =>  Left("Cannot resolve task " +
            Segments((Segment.Label(last) :: revSelectorsSoFar).reverse:_*).render
          )
          // Contents of `either` *must* be a `Task`, because we only select
          // methods returning `Task` in the discovery process
          case Some(either) => either.right.map(Seq(_))
        }


      case head :: tail =>
        val newRevSelectorsSoFar = head :: revSelectorsSoFar
        head match{
          case Segment.Label(singleLabel) =>
            if (singleLabel == "__") {

              val matching =
                obj.millInternal.modules
                  .map(resolve(tail, _, discover, rest, remainingCrossSelectors, newRevSelectorsSoFar))
                  .collect{case Right(vs) => vs}.flatten

              if (matching.nonEmpty)Right(matching)
              else Left("Cannot resolve module " + Segments(newRevSelectorsSoFar.reverse:_*).render)
            } else if (singleLabel == "_") {

              val matching =
                obj.millModuleDirectChildren
                  .map(resolve(tail, _, discover, rest, remainingCrossSelectors, newRevSelectorsSoFar))
                  .collect{case Right(vs) => vs}.flatten

              if (matching.nonEmpty)Right(matching)
              else Left("Cannot resolve module " + Segments(newRevSelectorsSoFar.reverse:_*).render)
            }else{

              obj.millInternal.reflectNestedObjects[mill.Module].find{
                _.millOuterCtx.segment == Segment.Label(singleLabel)
              } match{
                case Some(child: mill.Module) => resolve(tail, child, discover, rest, remainingCrossSelectors, newRevSelectorsSoFar)
                case None => Left("Cannot resolve module " + Segments(newRevSelectorsSoFar.reverse:_*).render)
              }
            }

          case Segment.Cross(cross) =>
            obj match{
              case c: Cross[_] =>
                if(cross == Seq("__")){
                  val matching =
                    for ((k, v) <- c.items)
                    yield resolve(tail, v.asInstanceOf[mill.Module], discover, rest, remainingCrossSelectors, newRevSelectorsSoFar)

                  val results = matching.collect{case Right(res) => res}.flatten

                  if (results.isEmpty) Left("Cannot resolve cross " + Segments(newRevSelectorsSoFar.reverse:_*).render)
                  else Right(results)
                } else if (cross.contains("_")){
                  val matching = for {
                    (k, v) <- c.items
                    if k.length == cross.length
                    if k.zip(cross).forall { case (l, r) => l == r || r == "_" }
                  } yield resolve(tail, v.asInstanceOf[mill.Module], discover, rest, remainingCrossSelectors, newRevSelectorsSoFar)

                  val results = matching.collect{case Right(res) => res}.flatten

                  if (results.isEmpty) Left("Cannot resolve cross " + Segments(newRevSelectorsSoFar.reverse:_*).render)
                  else Right(results)
                }else{
                  c.itemMap.get(cross.toList) match{
                    case Some(m: mill.Module) => resolve(tail, m, discover, rest, remainingCrossSelectors, newRevSelectorsSoFar)
                    case None => Left("Cannot resolve cross " + Segments(newRevSelectorsSoFar.reverse:_*).render)
                  }
                }
              case _ => Left("Cannot resolve cross " + Segments(newRevSelectorsSoFar.reverse:_*).render)
            }
        }

      case Nil => Left("Selector cannot be empty")
    }
  }
}
