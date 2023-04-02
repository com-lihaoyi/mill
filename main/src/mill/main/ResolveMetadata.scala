package mill.main

import mill.define._

object ResolveMetadata extends Resolve[String] {
  def singleModuleMeta(obj: Module, discover: Discover[_], isRootModule: Boolean): Seq[String] = {
    val modules = obj.millModuleDirectChildren.map(_.millModuleSegments.render)
    val targets =
      obj
        .millInternal
        .reflectAll[Target[_]]
        .map(_.toString)
    val commands =
      for {
        (cls, entryPoints) <- discover.value
        if cls.isAssignableFrom(obj.getClass)
        ep <- entryPoints
      } yield
        if (isRootModule) ep._2.name
        else s"$obj.${ep._2.name}"

    modules ++ targets ++ commands
  }

  def endResolveLabel(
      obj: Module,
      last: String,
      discover: Discover[_],
      rest: Seq[String]
  ): Either[String, Seq[String]] = {
    def direct = singleModuleMeta(obj, discover, obj.millModuleSegments.value.isEmpty)
    last match {
      case "__" =>
        Right(
          // Filter out our own module in
          obj.millInternal.modules.flatMap(m => singleModuleMeta(m, discover, m == obj))
        )
      case "_" => Right(direct)
      case _ =>
        direct.find(_.split('.').last == last) match {
          case None =>
            Resolve.errorMsgLabel(direct, Seq(Segment.Label(last)), obj.millModuleSegments.value)
          case Some(s) => Right(Seq(s))
        }
    }
  }

  def endResolveCross(
      obj: Module,
      last: List[String],
      discover: Discover[_],
      rest: Seq[String]
  ): Either[String, List[String]] = {
    obj match {
      case c: Cross[_] =>
        last match {
          case List("__") => Right(c.items.map(_._2.toString))
          case items =>
            c.items
              .filter(_._1.length == items.length)
              .filter(_._1.zip(last).forall { case (a, b) => b == "_" || a.toString == b })
              .map(_._2.toString) match {
              case Nil =>
                Resolve.errorMsgCross(
                  c.items.map(_._1.map(_.toString)),
                  last,
                  obj.millModuleSegments.value
                )
              case res => Right(res)
            }

        }
      case _ =>
        Left(
          Resolve.unableToResolve(Segment.Cross(last), obj.millModuleSegments.value) +
            Resolve.hintListLabel(obj.millModuleSegments.value)
        )
    }
  }
}
