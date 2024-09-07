package mill.api

import scala.concurrent.ExecutionContext

trait BlockableExecutionContext extends ExecutionContext with AutoCloseable {
  def blocking[T](t: => T): T
}
