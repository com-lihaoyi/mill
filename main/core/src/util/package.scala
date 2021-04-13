package mill

package object util {
  // Backwards compat stubs
  @deprecated("Use mill.api.Ctx instead", "mill after 0.9.6")
  val Ctx = mill.api.Ctx
  @deprecated("Use mill.api.Ctx instead", "mill after 0.9.6")
  type Ctx = mill.api.Ctx
}
