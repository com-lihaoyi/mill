package mill

package object api {
  val Result = mill.runner.api.Result
  type Result[+T] = mill.runner.api.Result[T]
  val Val = mill.runner.api.Val
  type Val = mill.runner.api.Val
  type Logger = mill.runner.api.Logger
  val Logger = mill.runner.api.Logger
  type SystemStreams = mill.runner.api.SystemStreams
  type ExecResult[+T] = mill.runner.api.ExecResult[T]
  val ExecResult = mill.runner.api.ExecResult
}
