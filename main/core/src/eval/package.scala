package mill

package object eval {
  // Backwards compatibility forwarders
  @deprecated("Use mill.api.Result instead")
  val Result = mill.api.Result
  @deprecated("Use mill.api.Result instead")
  type Result[+T] = mill.api.Result[T]

  @deprecated("Use mill.api.PathRef instead")
  val PathRef = mill.api.PathRef
  @deprecated("Use mill.api.PathRef instead")
  type PathRef = mill.api.PathRef

  @deprecated("Use mill.api.Logger instead")
  type Logger = mill.api.Logger
}
