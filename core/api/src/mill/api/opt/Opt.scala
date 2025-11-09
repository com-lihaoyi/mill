package mill.api.opt

import mill.api.daemon.internal.OptApi
import mill.api.JsonFormatters.given

import scala.annotation.targetName
import scala.language.implicitConversions

case class Opt private (value: Seq[Opt.OptTypes]) extends OptApi {
  override def toString(): String = value.mkString("")

  def map(conv: Opt.OptTypes => Opt.OptTypes): Opt = Opt(value.map(conv)*)

  private def startString: String =
    value.takeWhile(_.isInstanceOf[String]).collect { case s: String => s }.mkString("")

  def startsWith(prefix: String): Boolean = startString.startsWith(prefix)

  def mapStartString(rep: String => String): Opt = {
    val rest = value.dropWhile(_.isInstanceOf[String])
    Opt.apply((rep(startString) +: rest)*)
  }

  def containsPaths: Boolean = value.exists {
    case _: os.Path => true
    case _ => false
  }
}

object Opt {

  type OptTypes = (String | os.Path)

  @targetName("applyVarArg")
  def apply(value: OptTypes*): Opt = {
    // TODO: merge sequential strings
    new Opt(value.filter {
      case s: String if s.isEmpty => false
      case _ => true
    })
  }

  /**
   * Constructs a path from multiple path elements and a separator string.
   * Can be used to render classpaths.
   * Each path component will still be handled properly, e.g. mapped according to the current [[MappedPaths]] mapping.
   */
  def mkPath(paths: Seq[os.Path], prefix: String = "", sep: String, suffix: String = ""): Opt = {
    var needSep = false
    Opt(
      (
        Seq(prefix) ++
          paths.flatMap { path =>
            if (needSep)
              Seq(sep, path)
            else {
              needSep = true
              Seq(path)
            }
          } ++ Seq(suffix)
      )*
    )
  }

  def mkPlatformPath(paths: Seq[os.Path]): Opt = mkPath(paths, sep = java.io.File.pathSeparator)

//  given jsonReadWriter: upickle.ReadWriter[Opt] =
//    upickle.readwriter[Seq[(Option[String], Option[os.Path])]].bimap(
//      _.value.map {
//        case path: os.Path => (None, Some(path))
//        case str: String => (Some(str), None)
//      },
//      seq =>
//        Opt(seq.map {
//          case (Some(str), _) => str
//          case (_, Some(path)) => path
//        }*)
//    )

  given jsonReadWriter: upickle.ReadWriter[Opt] =
    upickle.readwriter[ujson.Value].bimap(
      opt =>
        if (!opt.containsPaths) ujson.Str(opt.toString())
        else opt.value.map {
          case str: String => ujson.Str(str)
          case path: os.Path => ujson.Obj("path" -> upickle.transform(path).to[ujson.Value])
        },
      {
        case ujson.Str(opt) => Opt(opt)
        case arr: ujson.Arr =>
          val elems = arr.value.map {
            case ujson.Str(opt) => opt
            case ujson.Obj(map) => upickle.read[os.Path](map("path"))
          }
          Opt(elems.toSeq*)
      }
    )

  //  given stringToOpt: Conversion[String, Opt] = (value: String) => Opt(value)
//  given osPathToOpt: Conversion[os.Path, Opt] = (value: os.Path) => Opt(value)

//  implicit def IterableToOpt[T](s: Iterable[T])(using f: T => Opt): Opt =
//    Opt(s.toSeq.flatMap(f(_).value))

  implicit def AllToOpt(o: String | os.Path | Opt): Opt = o match {
    case s: String => Opt(s)
    case p: os.Path => Opt(p)
    case o: Opt => o
  }

//  implicit def StringToOpt(s: String): Opt = Opt(s)
//
//  implicit def OsPathToOpt(p: os.Path): Opt = Opt(p)
//
//  implicit def OptToOpt(o: Opt): Opt = o

}
