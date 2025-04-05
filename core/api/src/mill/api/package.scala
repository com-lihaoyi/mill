package mill

package object api {
  val Result = mill.runner.api.Result
  type Result[+T] = mill.runner.api.Result[T]
  val Val = mill.runner.api.Val
  type Val = mill.runner.api.Val
}
