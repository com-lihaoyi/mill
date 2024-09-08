package mill.api

import scala.concurrent.ExecutionContext

trait BlockableExecutionContext extends ExecutionContext with AutoCloseable {
  def await[T](t: => scala.concurrent.Future[T]): T
}
