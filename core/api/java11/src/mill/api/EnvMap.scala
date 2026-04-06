package mill.api

import scala.collection.immutable.SortedMap

object EnvMap {

  private val WindowsEnvMapKeyOrdering: Ordering[String] =
    scala.math.Ordering.comparatorToOrdering(using String.CASE_INSENSITIVE_ORDER)

  def asEnvMap(env: Map[String, String]): Map[String, String] = {
    // 1. On Windows, we need to find env variable case-insensitive,
    //    therefore we wrap into a SortedMap with a case-insensitive key ordering
    // 2. SortedMap.from is already handling the case when the input-map is already
    //    a SortedMap with same ordering. That's also why we persist to ordering in a val.
    if (scala.util.Properties.isWin) SortedMap.from(env)(using WindowsEnvMapKeyOrdering)
    else env
  }

}
