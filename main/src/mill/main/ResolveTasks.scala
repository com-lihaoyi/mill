package mill.main

import mill.define._
import mill.main.ResolveMetadata.singleModuleMeta

object ResolveTasks extends Resolve[NamedTask[Any]] {

  def endResolveCross(
      obj: Module,
      last: List[String],
      discover: Discover[_],
      rest: Seq[String]
  ): Either[String, Seq[NamedTask[Any]]] = {
    obj match {
      case _: Cross[Module] =>
        Resolve.runDefault(obj, Segment.Cross(last), discover, rest).flatten.headOption match {
          case None =>
            Left(
              "Cannot find default task to evaluate for module " +
                Segments((obj.millModuleSegments.value :+ Segment.Cross(last)): _*).render
            )
          case Some(v) => v.map(Seq(_))
        }
      case _ =>
        Left(
          Resolve.unableToResolve(Segment.Cross(last), obj.millModuleSegments.value) +
            Resolve.hintListLabel(obj.millModuleSegments.value)
        )
    }
  }

  def endResolveLabel(
      obj: Module,
      last: String,
      discover: Discover[_],
      rest: Seq[String]
  ): Either[String, Seq[NamedTask[Any]]] = last match {
    case "__" =>
      Right(
        obj.millInternal.modules
          .filter(_ != obj)
          .flatMap(m => m.millInternal.reflectAll[NamedTask[_]])
      )
    case "_" => Right(obj.millInternal.reflectAll[NamedTask[_]])

    case _ =>
      val target =
        obj
          .millInternal
          .reflectSingle[NamedTask[_]](last)
          .map(Right(_))

      val command = Resolve.invokeCommand(
        obj,
        last,
        discover.asInstanceOf[Discover[Module]],
        rest
      ).headOption

      command
        .orElse(target)
        .orElse {
          Resolve.runDefault(
            obj,
            Segment.Label(last),
            discover,
            rest
          ).flatten.headOption
        } match {
        case None =>
          Resolve.errorMsgLabel(
            singleModuleMeta(obj, discover, obj.millModuleSegments.value.isEmpty),
            Seq(Segment.Label(last)),
            obj.millModuleSegments.value
          )

        // Contents of `either` *must* be a `Task`, because we only select
        // methods returning `Task` in the discovery process
        case Some(either) => either.map(Seq(_))
      }
  }
}
