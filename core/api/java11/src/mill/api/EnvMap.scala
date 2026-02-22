package mill.api

import scala.collection.immutable.SortedMap

object EnvMap {

  private val WindowsEnvMapKeyOrdering: Ordering[String] =
    scala.math.Ordering.comparatorToOrdering(using String.CASE_INSENSITIVE_ORDER)

  def asEnvMap(env: Map[String, String]): Map[String, String] = {
    // On Windows, we need to find env variable case-insensitive,
    // therefore we wrap into a SortedMap with a case-insensitive key ordering
    if (scala.util.Properties.isWin) SortedMap.from(env)(using WindowsEnvMapKeyOrdering)
    else env
  }

}
