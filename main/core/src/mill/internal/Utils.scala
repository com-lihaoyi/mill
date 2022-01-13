package mill.internal

private [mill] object Utils {
  def normalizeAmmoniteImportPath(segments: Seq[String]): Seq[String] = {
    def loop(l: List[String], up: Int): List[String] = l match {
      case ("^" | "$up") :: tail  => loop(tail, up + 1)
      case l @ "$file" :: "ammonite" :: Nil => List.fill(up)("$up") ::: l
      case head :: tail if up > 0 => loop(tail, up - 1)
      case head :: tail => head :: loop(tail, up)
      case Nil => Nil
    }
    loop(segments.reverse.toList, 0).reverse
  }
  def normalizeAmmoniteImportPath(cls: String): String = {
    normalizeAmmoniteImportPath(cls.split('.')).mkString(".")
  }
}
