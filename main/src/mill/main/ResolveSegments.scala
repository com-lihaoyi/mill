package mill.main

import mill.define._
import mill.main.ResolveMetadata.singleModuleMeta

object ResolveSegments extends Resolve[Segments] {

  override def endResolveCross(
      obj: Module,
      last: List[String],
      discover: Discover[_],
      rest: Seq[String]
  ): Either[String, Seq[Segments]] = {
    obj match {
      case c: Cross[_] =>
        last match {
          case List("__") => Right(c.crossModules.map(_.millModuleSegments))
          case items =>
            c.segmentsToModules
              .filter(_._1.length == items.length)
              .filter(_._1.zip(last).forall { case (a, b) => b == "_" || a == b })
              .map(_._2.millModuleSegments) match {
              case Nil =>
                Resolve.errorMsgCross(
                  c.segmentsToModules.map(_._1).toList,
                  last,
                  obj.millModuleSegments.value
                )
              case res => Right(res.toSeq)
            }
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
  ): Either[String, Seq[Segments]] = {
    val target =
      obj
        .millInternal
        .reflectSingle[Target[_]](last)
        .map(t => Right(t.ctx.segments))

    val command =
      Resolve
        .invokeCommand(obj, last, discover.asInstanceOf[Discover[Module]], rest)
        .headOption
        .map(_.map(_.ctx.segments))

    val module =
      obj.millInternal
        .reflectNestedObjects[Module]
        .find(_.millOuterCtx.segment == Segment.Label(last))
        .map(m => Right(m.millModuleSegments))

    command orElse target orElse module match {
      case None =>
        Resolve.errorMsgLabel(
          singleModuleMeta(obj, discover, obj.millModuleSegments.value.isEmpty),
          Seq(Segment.Label(last)),
          obj.millModuleSegments.value
        )

      case Some(either) => either.right.map(Seq(_))
    }
  }
}
