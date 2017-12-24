package mill.main

import mill.define.Task
import mill.define.Task.TaskModule
import mill.discover.{Mirror}
import ammonite.main.Router

object Resolve {
  def resolve[T, V](remainingSelector: List[Mirror.Segment],
                    hierarchy: Mirror[T, V],
                    obj: T,
                    rest: Seq[String],
                    remainingCrossSelectors: List[List[String]],
                    revSelectorsSoFar: List[Mirror.Segment]): Either[String, Task[Any]] = {

    remainingSelector match{
      case Mirror.Segment.Cross(_) :: Nil => Left("Selector cannot start with a [cross] segment")
      case Mirror.Segment.Label(last) :: Nil =>
        def target =
          hierarchy.targets
            .find(_.label == last)
            .map(x => Right(x.run(hierarchy.node(obj, remainingCrossSelectors))))

        def invokeCommand[V](mirror: Mirror[T, V], name: String) = for{
          cmd <- mirror.commands.find(_.name == name)
        } yield cmd.invoke(
          mirror.node(obj, remainingCrossSelectors),
          ammonite.main.Scripts.groupArgs(rest.toList)
        ) match {
          case Router.Result.Success(v) => Right(v)
          case _ => Left(s"Command failed $last")
        }

        def runDefault = for{
          (label, child) <- hierarchy.children
          if label == last
          res <- child.node(obj, remainingCrossSelectors) match{
            case taskMod: TaskModule => Some(invokeCommand(child, taskMod.defaultCommandName()))
            case _ => None
          }
        } yield res

        def command = invokeCommand(hierarchy, last)

        command orElse target orElse runDefault.headOption.flatten match{
          case None =>  Left("Cannot resolve task " + Mirror.renderSelector(
            (Mirror.Segment.Label(last) :: revSelectorsSoFar).reverse)
          )
          // Contents of `either` *must* be a `Task`, because we only select
          // methods returning `Task` in the discovery process
          case Some(either) => either.right.map{ case x: Task[Any] => x }
        }


      case head :: tail =>
        val newRevSelectorsSoFar = head :: revSelectorsSoFar
        head match{
          case Mirror.Segment.Label(singleLabel) =>
            hierarchy.children.collectFirst{
              case (label, child) if label == singleLabel => child
            } match{
              case Some(child) => resolve(tail, child, obj, rest, remainingCrossSelectors, newRevSelectorsSoFar)
              case None => Left("Cannot resolve module " + Mirror.renderSelector(newRevSelectorsSoFar.reverse))
            }

          case Mirror.Segment.Cross(cross) =>
            val Some((crossGen, childMirror)) = hierarchy.crossChildren
            val crossOptions = crossGen(hierarchy.node(obj, remainingCrossSelectors))
            if (crossOptions.contains(cross)){
              resolve(tail, childMirror, obj, rest, remainingCrossSelectors, newRevSelectorsSoFar)
            }else{
              Left("Cannot resolve cross " + Mirror.renderSelector(newRevSelectorsSoFar.reverse))
            }


        }

      case Nil => Left("Selector cannot be empty")
    }
  }
}
