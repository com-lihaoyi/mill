package mill.api.daemon.internal

trait TaskApi[+T] {
  def apply(): T

  /**
   * The input tasks that this task depends on. Used for traversing the task graph.
   */
  private[mill] def inputsApi: Seq[TaskApi[?]] = Nil

  /**
   * Whether this task is an input task (Task.Input, Task.Source, Task.Sources)
   * that has no upstream dependencies and re-evaluates every time.
   */
  private[mill] def isInputTask: Boolean = false

  /**
   * If this task is a worker, returns Some(this), otherwise None.
   * Used for identifying workers when traversing the task graph.
   */
  private[mill] def asWorkerApi: Option[TaskApi[?]] = None

  /**
   * If this task is a worker, returns its full name (segments.render), otherwise None.
   * Used for identifying workers when building dependency graphs.
   */
  private[mill] def workerNameApi: Option[String] = None
}
