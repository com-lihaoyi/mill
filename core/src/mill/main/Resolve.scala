package mill.main

import mill.define._
import mill.define.TaskModule
import ammonite.main.Router
import ammonite.main.Router.EntryPoint

object Resolve {
  def resolve[T, V](
      remainingSelector: List[Segment],
      obj: mill.Module,
      discover: Discover,
      rest: Seq[String],
      remainingCrossSelectors: List[List[String]],
      revSelectorsSoFar: List[Segment]): Either[String, Task[Any]] = {

    remainingSelector match {
      case Segment.Cross(_) :: Nil => Left("Selector cannot start with a [cross] segment")
      case Segment.Label(last) :: Nil =>
        val target =
          obj.millInternal
            .reflect[Target[_]]
            .find(_.label == last)
            .map(Right(_))

        def invokeCommand[V](target: mill.Module, name: String) = {
          for (cmd <- discover.value.get(target.getClass).toSeq.flatten.find(_.name == name))
            yield
              cmd
                .asInstanceOf[EntryPoint[mill.Module]]
                .invoke(target, ammonite.main.Scripts.groupArgs(rest.toList)) match {
                case Router.Result.Success(v) => Right(v)
                case _ => Left(s"Command failed $last")
              }
        }

        val runDefault = for {
          child <- obj.millInternal.reflectNestedObjects[mill.Module]
          if child.millOuterCtx.segment == Segment.Label(last)
          res <- child match {
            case taskMod: TaskModule => Some(invokeCommand(child, taskMod.defaultCommandName()))
            case _ => None
          }
        } yield res

        val command = invokeCommand(obj, last)

        command orElse target orElse runDefault.headOption.flatten match {
          case None =>
            Left(
              "Cannot resolve task " +
                Segments((Segment.Label(last) :: revSelectorsSoFar).reverse: _*).render)
          // Contents of `either` *must* be a `Task`, because we only select
          // methods returning `Task` in the discovery process
          case Some(either) => either.right.map { case x: Task[Any] => x }
        }

      case head :: tail =>
        val newRevSelectorsSoFar = head :: revSelectorsSoFar
        head match {
          case Segment.Label(singleLabel) =>
            obj.millInternal.reflectNestedObjects[mill.Module].find {
              _.millOuterCtx.segment == Segment.Label(singleLabel)
            } match {
              case Some(child: mill.Module) =>
                resolve(tail, child, discover, rest, remainingCrossSelectors, newRevSelectorsSoFar)
              case None =>
                Left("Cannot resolve module " + Segments(newRevSelectorsSoFar.reverse: _*).render)
            }

          case Segment.Cross(cross) =>
            obj match {
              case c: Cross[_] =>
                c.itemMap.get(cross.toList) match {
                  case Some(m: mill.Module) =>
                    resolve(tail, m, discover, rest, remainingCrossSelectors, newRevSelectorsSoFar)
                  case None =>
                    Left(
                      "Cannot resolve cross " + Segments(newRevSelectorsSoFar.reverse: _*).render)

                }
              case _ =>
                Left("Cannot resolve cross " + Segments(newRevSelectorsSoFar.reverse: _*).render)
            }
        }

      case Nil => Left("Selector cannot be empty")
    }
  }
}
