package mill.modules

import java.util.jar.JarFile
import java.util.regex.Pattern

import upickle.default.{ReadWriter => RW}

object Assembly {

  val defaultRules: Seq[Rule] = Seq(
    Rule.Append("reference.conf"),
    Rule.Exclude(JarFile.MANIFEST_NAME),
    Rule.ExcludePattern(".*\\.[sS][fF]"),
    Rule.ExcludePattern(".*\\.[dD][sS][aA]"),
    Rule.ExcludePattern(".*\\.[rR][sS][aA]")
  )

  sealed trait Rule extends Product with Serializable
  object Rule {
    case class Append(path: String) extends Rule

    object AppendPattern {
      def apply(pattern: String): AppendPattern = AppendPattern(Pattern.compile(pattern))
    }
    case class AppendPattern(pattern: Pattern) extends Rule

    case class Exclude(path: String) extends Rule

    object ExcludePattern {
      def apply(pattern: String): ExcludePattern = ExcludePattern(Pattern.compile(pattern))
    }
    case class ExcludePattern(pattern: Pattern) extends Rule

    implicit lazy val appendRW: RW[Append] = upickle.default.macroRW
    implicit lazy val appendPatternRW: RW[AppendPattern] = upickle.default.macroRW
    implicit lazy val excludeRW: RW[Exclude] = upickle.default.macroRW
    implicit lazy val excludePatternRW: RW[ExcludePattern] = upickle.default.macroRW
    implicit lazy val regexRW: RW[Pattern] = upickle.default.readwriter[String].bimap[Pattern](
      _.pattern(), Pattern.compile
    )
    implicit lazy val rulesFormat: RW[Rule] = {
      RW.merge(appendRW, appendPatternRW, excludeRW, excludePatternRW)
    }
  }
}
