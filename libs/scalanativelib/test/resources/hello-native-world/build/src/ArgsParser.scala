package hello

object ArgsParser {
  def parse(s: String): Seq[String] = s.split(":").toSeq
}
