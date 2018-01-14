package mill.main

import mill.define.{Segment, Segments, Target, Task}
import mill.define.Task.TaskModule
import mill.discover.Mirror
import ammonite.main.Router

object Resolve {
  def resolve[T, V](remainingSelector: List[Segment],
                    hierarchy: Mirror[T, V],
                    obj: T,
                    rest: Seq[String],
                    remainingCrossSelectors: List[List[String]],
                    revSelectorsSoFar: List[Segment]): Either[String, Task[Any]] = {

    remainingSelector match{
      case Segment.Cross(_) :: Nil => Left("Selector cannot start with a [cross] segment")
      case Segment.Label(last) :: Nil =>
        def target =
          hierarchy.node(obj, remainingCrossSelectors).asInstanceOf[mill.Module]
            .reflect[Target[_]]
            .find(_.label == last)
            .map(Right(_))

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
          case None =>  Left("Cannot resolve task " +
            Segments((Segment.Label(last) :: revSelectorsSoFar).reverse:_*).render
          )
          // Contents of `either` *must* be a `Task`, because we only select
          // methods returning `Task` in the discovery process
          case Some(either) => either.right.map{ case x: Task[Any] => x }
        }


      case head :: tail =>
        val newRevSelectorsSoFar = head :: revSelectorsSoFar
        head match{
          case Segment.Label(singleLabel) =>
            hierarchy.children.collectFirst{
              case (label, child) if label == singleLabel => child
            } match{
              case Some(child) => resolve(tail, child, obj, rest, remainingCrossSelectors, newRevSelectorsSoFar)
              case None => Left("Cannot resolve module " + Segments(newRevSelectorsSoFar.reverse:_*).render)
            }

          case Segment.Cross(cross) =>
            val Some((crossGen, childMirror)) = hierarchy.crossChildren
            val crossOptions = crossGen(hierarchy.node(obj, remainingCrossSelectors))
            if (crossOptions.contains(cross)){
              resolve(tail, childMirror, obj, rest, remainingCrossSelectors, newRevSelectorsSoFar)
            }else{
              Left("Cannot resolve cross " + Segments(newRevSelectorsSoFar.reverse:_*).render)
            }


        }

      case Nil => Left("Selector cannot be empty")
    }
  }
}
