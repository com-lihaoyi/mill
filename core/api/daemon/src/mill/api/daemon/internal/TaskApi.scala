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

  private[mill] def asWorkerApi: Option[TaskApi[?]] = None
  private[mill] def workerNameApi: Option[String] = None
}
