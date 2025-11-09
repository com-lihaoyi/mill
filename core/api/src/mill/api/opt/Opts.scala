package mill.api.opt

import mill.api.daemon.internal.OptsApi

import scala.annotation.targetName
import scala.language.implicitConversions

case class Opts private (override val value: Seq[OptGroup]) extends OptsApi {
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

//  given jsonReadWriter: upickle.ReadWriter[Opts] =
//    upickle.readwriter[Seq[OptGroup]].bimap(
//      _.value,
//      Opts(_*)
//    )

  given jsonReadWriter: upickle.ReadWriter[Opts] =
    upickle.readwriter[ujson.Arr].bimap(
      { opts =>
        // We always serialize as a seq of groups
        opts.value.map { group =>
          if(group.size == 1 && !group.head.containsPaths) {
            ujson.Str(group.head.toString())
          }
          else
            upickle.transform(group).to[ujson.Value]
        }
//        upickle.transform(opts.value).to[ujson.Value]
      },
      {
        case arr: ujson.Arr =>
          Opts(
            arr.value.map {
              // The default case, a seq of groups
              case e: ujson.Str => OptGroup(upickle.read[Opt](e))
              // special case, a flat Opt, so we can also read simple ["opt1", "opt2"] arrays
              // which is what we want to use in YAML build files
              case g => upickle.read[OptGroup](g)
            }.toSeq*
          )
      }
    )

}
