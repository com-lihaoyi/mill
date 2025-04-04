package mill

package object api {
  val SystemStreams = mill.runner.api.SystemStreams
  type SystemStreams = mill.runner.api.SystemStreams
  val Result = mill.runner.api.Result
  type Result[+T] = mill.runner.api.Result[T]
  val Val = mill.runner.api.Val
  type Val = mill.runner.api.Val
  val Logger = mill.runner.api.Logger
  type Logger = mill.runner.api.Logger
}
