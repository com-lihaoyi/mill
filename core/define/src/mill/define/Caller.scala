package mill.define

case class Caller[+T](value: T)
object Caller {
  def apply[T]()(implicit c: Caller[T]) = c.value

  /* basically a poison-pill to check that the Module defined version is enough */
  inline given generate: Caller[Nothing] = defaultCaller

  @annotation.compileTimeOnly("No enclosing scope, this is a bug")
  def defaultCaller: Caller[Nothing] = Caller(null.asInstanceOf[Nothing])
}
