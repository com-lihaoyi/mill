package mill.javalib.dependency

import mainargs.TokensReader

sealed trait Format {
  def name: String = toString
}

object Format {
  case object PerModule extends Format
  case object PerDependency extends Format

  implicit object FormatRead extends TokensReader.Simple[Format] {
    override def shortName: String = "format"
    override def read(strs: scala.Seq[String]): Either[String, Format] = {
      val all = Seq[Format](Format.PerModule, Format.PerDependency)
      strs.headOption
        .flatMap(n => all.find(f => f.name == n))
        .toRight(strs.headOption
          .map(f => s"Unknown format: ${f}")
          .getOrElse("Missing format") +
          s". Possible formats: ${all.mkString(", ")}")
    }
  }
}
