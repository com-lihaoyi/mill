package mill.define

/**
 * Represents the topologically sorted set of tasks
 */
final class TopoSorted private[mill] (val values: IndexedSeq[mill.define.Task[?]])
