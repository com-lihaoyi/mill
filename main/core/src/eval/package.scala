package mill

package object eval {
  // Backwards compatibility forwarders
  val Result = mill.api.Result
  type Result[+T] = mill.api.Result[T]

  val PathRef = mill.api.PathRef
  type PathRef = mill.api.PathRef

  type Logger = mill.api.Logger
}
