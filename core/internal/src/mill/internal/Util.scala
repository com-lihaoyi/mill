package mill.internal

import scala.reflect.NameTransformer.encode

private[mill] object Util {

  val alphaKeywords: Set[String] = Set(
    "abstract",
    "case",
    "catch",
    "class",
    "def",
    "do",
    "else",
    "enum",
    "export",
    "extends",
    "false",
    "final",
    "finally",
    "forSome",
    "for",
    "given",
    "if",
    "implicit",
    "import",
    "lazy",
    "match",
    "new",
    "null",
    "object",
    "override",
    "package",
    "private",
    "protected",
    "return",
    "sealed",
    "super",
    "then",
    "this",
    "throw",
    "trait",
    "try",
    "true",
    "type",
    "val",
    "var",
    "while",
    "with",
    "yield",
    "_",
    "macro"
  )

  def backtickWrap(s: String): String = s match {
    case s"`$v`" => s
    case _ => if (encode(s) == s && !alphaKeywords.contains(s)) s
      else "`" + s + "`"
  }

  def parsedHeaderData(headerData: String): Map[String, ujson.Value] = {
    import org.snakeyaml.engine.v2.api.{Load, LoadSettings}
    val loaded = new Load(LoadSettings.builder().build()).loadFromString(headerData)

    // recursively convert java data structure to ujson.Value
    def rec(x: Any): ujson.Value = {
      x match {
        case d: java.util.Date => ujson.Str(d.toString)
        case s: String => ujson.Str(s)
        case d: Double => ujson.Num(d)
        case d: Int => ujson.Num(d)
        case d: Long => ujson.Num(d)
        case true => ujson.True
        case false => ujson.False
        case null => ujson.Null
        case m: java.util.Map[Object, Object] =>
          import collection.JavaConverters._
          val scalaMap = m.asScala
          ujson.Obj.from(scalaMap.map { case (k, v) => (k.toString, rec(v)) })
        case l: java.util.List[Object] =>
          import collection.JavaConverters._
          val scalaList: collection.Seq[Object] = l.asScala
          ujson.Arr.from(scalaList.map(rec))
      }
    }

    rec(loaded).objOpt.getOrElse(Map.empty[String, ujson.Value]).toMap
  }
}
