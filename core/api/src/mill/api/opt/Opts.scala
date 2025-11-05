package mill.api.opt

import mill.api.daemon.internal.OptsApi

import scala.language.implicitConversions

case class Opts private (override val value: OptGroup*) extends OptsApi
    derives upickle.ReadWriter {

  def toStringSeq: Seq[String] = value.flatMap(_.toStringSeq)
  override def toString(): String = value.mkString("Opts(", ", ", ")")

  def concat(suffix: Opts): Opts = Opts(value ++ suffix.value)
  @`inline` final def ++(suffix: Opts): Opts = concat(suffix)

  def containsPaths: Boolean = value.exists(_.containsPaths)

  def isEmpty: Boolean = value.isEmpty
  def filterGroup(pred: OptGroup => Boolean): Opts = Opts(value.filter(pred)*)
  def mapGroup(f: OptGroup => OptGroup): Opts = Opts(value.map(f)*)
  def flatMap(f: OptGroup => Seq[OptGroup]): Opts = Opts(value.flatMap(f)*)
}

object Opts {
  def apply(value: OptGroup*): Opts = new Opts(value.filter(!_.isEmpty))
//  @targetName("applyUnion")
//  def apply(value: (Opt | OptGroup | Seq[Opt])*): Opts = Opts(value.flatMap {
//    case a: Opt => Seq(OptGroup(a))
//    case a: OptGroup => Seq(a)
//    case s: Iterable[Opt] => s.map(OptGroup(_))
//  })
}
