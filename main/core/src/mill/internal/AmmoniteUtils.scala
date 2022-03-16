package mill.internal

@mill.api.internal
private[mill] object AmmoniteUtils {
  def normalizeAmmoniteImportPath(segments: Seq[String]): Seq[String] = {
    def loop(l: List[String], up: Int): List[String] = l match {
      case ("^" | "$up") :: tail => loop(tail, up + 1)
      case l @ "$file" :: "ammonite" :: Nil => List.fill(up)("$up") ::: l
      case head :: tail if up > 0 => loop(tail, up - 1)
      case head :: tail => head :: loop(tail, up)
      case Nil => Nil
    }
    val reversed = segments.reverse.toList
    val withoutCompanions = reversed match {
      case head :: tail =>
        head.takeWhile(_ != '$') :: tail
      case Nil => Nil
    }
    loop(withoutCompanions, 0).reverse
  }
  def normalizeAmmoniteImportPath(cls: String): String = {
    normalizeAmmoniteImportPath(cls.split('.').toIndexedSeq).mkString(".")
  }
}
