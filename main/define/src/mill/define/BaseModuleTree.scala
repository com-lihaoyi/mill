package mill.define

/**
 * Data structure containing the metadata of all [[BaseModule]]s defined in a Mill build, as well
 * as pre-computed data structures for common lookup patterns during the target resolution process
 */
class BaseModuleTree(value: Seq[(Seq[String], BaseModule)]) {
  private val prefixForClass0: Map[Class[_], Seq[String]] = value.map { case (p, m) =>
    (m.getClass, p)
  }.toMap
  def prefixForClass(cls: Class[_]): Option[Seq[String]] = prefixForClass0.get(cls)

  private val lookupByParent0: Map[Option[Seq[String]], Seq[(Seq[String], BaseModule)]] =
    value.groupBy {
      case (Nil, _) => None
      case (xs :+ last, _) => Some(xs)
    }

  def lookupByParent(parentPrefixOpt: Option[Seq[String]]): Seq[(Seq[String], BaseModule)] =
    lookupByParent0.getOrElse(parentPrefixOpt, Nil)

  def rootModule: BaseModule = lookupByParent(None).head._2
  val allPossibleNames: Set[String] = {

    value
      .flatMap(_._2.millDiscover.value.values)
      .flatMap{ t =>t._1 ++ t._2.map(_.defaultName) }
      .toSet
  }

  val targetNamesByClass = value
    .flatMap{  case (segs, base) => base.millDiscover.value.mapValues(_._1.toSet) }
    .toMap
}

object BaseModuleTree {
  def from(rootModules: Seq[BaseModule]): BaseModuleTree = {
    new BaseModuleTree(
      rootModules
        .map { m =>
          val parts = m.getClass.getName match {
            case s"build.$partString.package$$" => partString.split('.')
            case s"build.${partString}_$$$last$$" => partString.split('.')
            case _ => Array[String]()
          }

          (parts, m)
        }
    )
  }
}
