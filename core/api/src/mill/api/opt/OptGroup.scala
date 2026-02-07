package mill.api.opt

import mill.api.daemon.experimental
import mill.api.daemon.internal.OptGroupApi

import scala.annotation.targetName
import scala.language.implicitConversions

/**
 * A set of options, which are used together
 */
@experimental
case class OptGroup private (value: Seq[Opt]) extends OptGroupApi {

  override def toString(): String = value.mkString("(", ", ", ")")

  def isEmpty: Boolean = value.isEmpty

  def size: Int = value.size

  def containsPaths: Boolean = value.exists(_.containsPaths)

  def head: Opt = value.head
  def headOption: Option[Opt] = value.headOption

  def toStringSeq: Seq[String] = value.map(_.toString())

  def concat(suffix: OptGroup): OptGroup = new OptGroup(value ++ suffix.value)

  @`inline` final def ++(suffix: OptGroup): OptGroup = concat(suffix)

}

@experimental
object OptGroup {
  @targetName("applyVarAar")
  def apply(opts: (String | os.Path | Opt | Seq[(String | os.Path | Opt)])*): OptGroup = {
    val opts0 = opts.flatMap {
      case s: String => Seq(Opt(s))
      case p: os.Path => Seq(Opt(p))
      case o: Opt => Seq(o)
      case o: Seq[(String | os.Path | Opt)] =>
        o.map {
          case s: String => Opt(s)
          case p: os.Path => Opt(p)
          case o: Opt => o
        }
    }
    new OptGroup(opts0)
  }

  def when(cond: Boolean)(value: Opt*): OptGroup = if (cond) OptGroup(value*) else OptGroup()

  given jsonReadWriter: upickle.ReadWriter[OptGroup] =
    upickle.readwriter[Seq[Opt]].bimap(
      _.value,
      OptGroup(_*)
    )

}
