package mill.api.opt

import mill.api.daemon.internal.OptsApi

import scala.annotation.targetName
import scala.language.implicitConversions

case class Opts private (override val value: Seq[OptGroup]) extends OptsApi
    derives upickle.ReadWriter {
  require(value.forall(!_.isEmpty))

  def toStringSeq: Seq[String] = value.flatMap(_.toStringSeq)
  override def toString(): String = value.mkString("Opts(", ", ", ")")

  def concat(suffix: Opts): Opts = Opts(value ++ suffix.value)
  @`inline` final def ++(suffix: Opts): Opts = concat(suffix)

  def containsPaths: Boolean = value.exists(_.containsPaths)

  def isEmpty: Boolean = value.isEmpty
  def filterGroup(pred: OptGroup => Boolean): Opts = Opts.apply(value.filter(pred)*)
  def mapGroup(f: OptGroup => OptGroup): Opts = Opts.apply(value.map(f)*)
  def flatMap(f: OptGroup => Seq[OptGroup]): Opts = Opts.apply(value.flatMap(f)*)
}

object Opts {
  @targetName("applyVarArgUnion")
//  def apply(value: OptGroup*): Opts = new Opts(value.filter(!_.isEmpty))
//  def apply(value: (String | os.Path | Opt | OptGroup | Seq[Opt])*): Opts = Opts(value.flatMap {
//    case a: Opt => Seq(OptGroup(a))
//    case a: OptGroup => Seq(a)
//    case s: Iterable[Opt] => s.map(OptGroup(_))
//  })

  def apply(
      opts: (String | os.Path | Opt | IterableOnce[(String | os.Path | Opt)] | OptGroup | Opts)*
  ): Opts = {
    val groups = opts.flatMap {
      // Seq of OptGroup
      case s: String => Seq(OptGroup(s))
      case p: os.Path => Seq(OptGroup(p))
      case o: Opt => Seq(OptGroup(o))
      case o: IterableOnce[(String | os.Path | Opt)] => Seq.from(o).map(OptGroup(_))
      case o: OptGroup => Seq(o)
      case o: Opts => o.value
    }
    new Opts(groups.filter(!_.isEmpty))
  }
}
