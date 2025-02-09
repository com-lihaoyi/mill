package mill.exec

import mill.api.Result

case class TaskResult[T](result: Result[T], recalc: () => Result[T]) {
  def map[V](f: T => V): TaskResult[V] = TaskResult[V](
    result.map(f),
    () => recalc().map(f)
  )
}
