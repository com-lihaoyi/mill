package mill.exec

import mill.api.{ExecResult, Result}

case class TaskResult[T](result: ExecResult[T], recalc: () => ExecResult[T]) {
  def map[V](f: T => V): TaskResult[V] = TaskResult[V](
    result.map(f),
    () => recalc().map(f)
  )
}
