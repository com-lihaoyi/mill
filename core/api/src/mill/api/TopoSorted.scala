package mill.api

/**
 * Represents the topologically sorted set of tasks
 *
 * `values`is guaranteed to be topological sorted and cycle free.
 * That's why the constructor is package private.
 *
 * @see [[PlanImpl.topoSorted]]
 */
final class TopoSorted private[mill] (val values: IndexedSeq[mill.api.Task[?]])
