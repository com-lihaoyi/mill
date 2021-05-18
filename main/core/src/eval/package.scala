package mill

package object eval {
  // Backwards compatibility forwarders
  @deprecated("Use mill.api.Result instead", "mill after 0.9.6")
  val Result = mill.api.Result
  @deprecated("Use mill.api.Result instead", "mill after 0.9.6")
  type Result[+T] = mill.api.Result[T]

  @deprecated("Use mill.api.PathRef instead", "mill after 0.9.6")
  val PathRef = mill.api.PathRef
  @deprecated("Use mill.api.PathRef instead", "mill after 0.9.6")
  type PathRef = mill.api.PathRef

  @deprecated("Use mill.api.Logger instead", "mill after 0.9.6")
  type Logger = mill.api.Logger
}
