package mill.api

/**
 * Represents the outcome of Mill planning: a [[MultiBiMap]] containing
 * the transitive tasks upstream of the tasks selected by the user,
 * grouped by the "terminal" named or selected task downstream of each group
 */
final class Plan private[mill] (val sortedGroups: MultiBiMap[Task[?], Task[?]]) {
  def transitive: IndexedSeq[Task[?]] = sortedGroups.values().flatten.toVector
}
