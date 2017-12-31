package jawn

/**
 * NullFacade discards all JSON AST information.
 *
 * This is the simplest possible facade. It could be useful for
 * checking JSON for correctness (via parsing) without worrying about
 * saving the data.
 *
 * It will always return () on any successful parse, no matter the
 * content.
 */
object NullFacade extends Facade[Unit] {

  case class NullContext(isObj: Boolean) extends FContext[Unit] {
    def add(s: CharSequence): Unit = ()
    def add(v: Unit): Unit = ()
    def finish: Unit = ()
  }

  val singleContext: FContext[Unit] = NullContext(false)
  val arrayContext: FContext[Unit] = NullContext(false)
  val objectContext: FContext[Unit] = NullContext(true)

  def jnull(): Unit = ()
  def jfalse(): Unit = ()
  def jtrue(): Unit = ()
  def jnum(s: CharSequence, decIndex: Int, expIndex: Int): Unit = ()
  def jstring(s: CharSequence): Unit = ()
}
