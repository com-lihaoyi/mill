package mill.define

case class Caller(value: Any)
object Caller {
  def apply()(implicit c: Caller) = c.value

  /* basically a poison-pill to check that the Module defined version is enough */
  inline given generate: Caller = defaultCaller

  @annotation.compileTimeOnly("No enclosing scope, this is a bug")
  def defaultCaller: Caller = Caller(null)
}
