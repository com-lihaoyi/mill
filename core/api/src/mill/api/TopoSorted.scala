package mill.api

/**
 * Represents the topologically sorted set of tasks
 */
final class TopoSorted private[mill] (val values: IndexedSeq[mill.api.Task[?]])
