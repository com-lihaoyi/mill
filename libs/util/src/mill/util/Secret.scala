package mill.util

/**
 * A value that makes sure it doesn't print anything on [[toString]].
 *
 * @note this is not a `case class` to prevent it being accidentally printed with [[pprint]].
 */
class Secret[+A](val value: A) {
  override def toString: String = "<REDACTED>"

  private def canEqual(other: Any): Boolean = other.isInstanceOf[Secret[?]]

  override def equals(other: Any): Boolean = other match {
    case that: Secret[_] => that.canEqual(this) && value == that.value
    case _ => false
  }

  override def hashCode(): Int = {
    val state = Seq(value)
    state.map(_.hashCode()).foldLeft(0)((a, b) => 31 * a + b)
  }
}
object Secret {
  def unpack[A](secretLike: PossiblySecret[A]): A = secretLike match {
    case s: Secret[A @unchecked] => s.value
    case a: A @unchecked => a
  }
}

type PossiblySecret[+A] = A | Secret[A]
