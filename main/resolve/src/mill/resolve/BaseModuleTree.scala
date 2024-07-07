package mill.resolve

import mill.define.BaseModule

/**
 * Data structure containing the metadata of all [[BaseModule]]s defined in a Mill build, as well
 * as pre-computed data structures for common lookup patterns during the target resolution process
 */
class BaseModuleTree(value: Seq[(Seq[String], BaseModule)]){
  private val prefixForClass0: Map[Class[_], Seq[String]] = value.map{case (p, m) => (m.getClass, p)}.toMap
  def prefixForClass(cls: Class[_]): Option[Seq[String]] = prefixForClass0.get(cls)

  private val lookupByParent0: Map[Option[Seq[String]], Seq[(Seq[String], BaseModule)]] = value.groupBy{
    case (Nil, _) => None
    case (xs :+ last, _) => Some(xs)
  }

  def lookupByParent(parentPrefixOpt: Option[Seq[String]]) = lookupByParent0.getOrElse(parentPrefixOpt, Nil)

  val allPossibleNames = value.flatMap(_._2.millDiscover.value.values).flatMap(_._1).toSet
}
