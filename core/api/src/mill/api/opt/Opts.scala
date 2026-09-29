package mill.api.opt

import mill.api.daemon.experimental
import mill.api.daemon.internal.OptsApi
import os.Path

import scala.annotation.targetName
import scala.language.implicitConversions

@experimental
case class Opts private (override val value: Seq[OptGroup]) extends OptsApi {
  require(value.forall(!_.isEmpty))

  def toStringSeq: Seq[String] = value.flatMap(_.toStringSeq)
  override def toString(): String = value.mkString("Opts(", ", ", ")")

  def concat(suffix: Opts): Opts = Opts(value ++ suffix.value)
  @`inline` final def ++(suffix: Opts): Opts = concat(suffix)

  def containsPaths: Boolean = value.exists(_.containsPaths)

  def isEmpty: Boolean = value.isEmpty
  def nonEmpty: Boolean = value.nonEmpty
  def filterGroup(pred: OptGroup => Boolean): Opts = Opts.apply(value.filter(pred)*)
  def mapGroup(f: OptGroup => OptGroup): Opts = Opts.apply(value.map(f)*)
  def flatMap(f: OptGroup => Seq[OptGroup]): Opts = Opts.apply(value.flatMap(f)*)
}

@experimental
object Opts {
  @targetName("applyVarArgUnion")
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

  trait When {
    def apply(
        opts: (String | os.Path | Opt | IterableOnce[(String | os.Path | Opt)] | OptGroup | Opts)*
    ): Opts
  }

  def when(cond: Boolean): When = if (cond) {
    new When {
      override def apply(
          opts: (String | Path | Opt | IterableOnce[String | Path | Opt] | OptGroup | Opts)*
      ): Opts =
        Opts.apply(opts*)
    }

  } else {
    new When {
      override def apply(
          opts: (String | Path | Opt | IterableOnce[String | Path | Opt] | OptGroup | Opts)*
      ): Opts =
        Opts()
    }

  }

  given jsonReadWriter: upickle.ReadWriter[Opts] =
    upickle.readwriter[ujson.Arr].bimap(
      { opts =>
        // We always serialize as a seq of groups
        opts.value.map { group =>
          if (group.size == 1 && !group.head.containsPaths) {
            ujson.Str(group.head.toString())
          } else
            upickle.transform(group).to[ujson.Value]
        }
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
