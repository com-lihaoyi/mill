package mill.api.opt

import mill.api.daemon.internal.OptGroupApi

import scala.annotation.targetName
import scala.language.implicitConversions

/**
 * A set of options, which are used together
 */
case class OptGroup private (value: Seq[Opt]) extends OptGroupApi
    derives upickle.ReadWriter {
  override def toString(): String = value.mkString("(", ", ", ")")

  def isEmpty: Boolean = value.isEmpty

  def containsPaths: Boolean = value.exists(_.containsPaths)

  def head: Opt = value.head

  def toStringSeq: Seq[String] = value.map(_.toString())

  def concat(suffix: OptGroup): OptGroup = new OptGroup(value ++ suffix.value)

  @`inline` final def ++(suffix: OptGroup): OptGroup = concat(suffix)

}

object OptGroup {
  @targetName("applyVarAar")
  def apply(opts: (String | os.Path | Opt | Seq[(String | os.Path | Opt)])*): OptGroup = new OptGroup(opts.flatMap {
    case s: String => Seq(Opt(s))
    case p: os.Path => Seq(Opt(p))
    case o: Opt => Seq(o)
    case o: Seq[(String | os.Path | Opt)] => o.map {
      case s: String => Opt(s)
      case p: os.Path => Opt(p)
      case o: Opt => o
    }
  })
//  @targetName("applyIterable")
//  def apply[T](opts: T*)(using f: T => Opt): OptGroup = new OptGroup(opts.map(f(_)))

  def when(cond: Boolean)(value: Opt*): OptGroup = if (cond) OptGroup(value*) else OptGroup()

//  given optsToOptGroup: Conversion[(OptTypes, OptTypes), OptGroup] =
//    (tuple: (OptTypes, OptTypes)) =>
//      OptGroup(Opt(tuple._1), Opt(tuple._2))

  implicit def StringToOptGroup(s: String): OptGroup = OptGroup(Seq(Opt(s)))

  implicit def OsPathToOptGroup(p: os.Path): OptGroup = OptGroup(Seq(Opt(p)))

  implicit def OptToOptGroup(o: Opt): OptGroup = OptGroup(Seq(o))

  implicit def IterableToOptGroup[T](s: Iterable[T])(using f: T => OptGroup): OptGroup =
    OptGroup(s.toSeq.flatMap(f(_).value))

//  implicit def ArrayToOptGroup[T](s: Array[T])(using f: T => OptGroup): OptGroup =
//    OptGroup(s.flatMap(f(_).value))

}
