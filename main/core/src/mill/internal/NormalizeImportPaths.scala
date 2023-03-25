package mill.internal

@mill.api.internal
private[mill] object NormalizeImportPaths {
  // Mapping replicated from the Scala compiler
  // https://github.com/scala/scala/blob/8a2cf63ee5bad8c8c054f76464de0e10226516a0/src/library/scala/reflect/NameTransformer.scala#L45
  private val symbolsClassesMapping = Map(
    '=' -> "$eq",
    '<' -> "$less",
    '-' -> "$minus",
    '#' -> "$hash",
    '?' -> "$qmark",
    '+' -> "$plus",
    '*' -> "$times",
    '%' -> "$percent",
    '&' -> "$amp",
    '!' -> "$bang",
    '|' -> "$bar",
    '→' -> "$u2192",
    '\\' -> "$bslash",
    ':' -> "$colon",
    '~' -> "$tilde",
    '/' -> "$div",
    '>' -> "$greater"
  )
  private val removeInnerClassesRegex = {
    val toIgnore = symbolsClassesMapping.values.map(_.stripPrefix("$")).mkString("|")
    s"\\$$(?!$toIgnore).*"
  }
  def normalizeImportPath(segments: Seq[String]): Seq[String] = {
    def normalized(segment: String): String = {
      segment.flatMap(symbolsClassesMapping.withDefault(_.toString()))
    }
    def loop(l: List[String], up: Int): List[String] = l match {
      case ("^" | "$up") :: tail => loop(tail, up + 1)
      case l @ "millbuild" :: Nil => List.fill(up)("$up") ::: l
      case head :: tail if up > 0 => loop(tail, up - 1)
      case head :: tail => normalized(head) :: loop(tail, up)
      case Nil => Nil
    }
    val reversed = segments.reverse.toList
    val withoutCompanions = reversed match {
      case head :: tail =>
        head.replaceAll(removeInnerClassesRegex, "") :: tail
      case Nil => Nil
    }
    loop(withoutCompanions, 0).reverse
  }
  def normalizeImportPath(cls: String): String = {
    normalizeImportPath(cls.split('.').toIndexedSeq).mkString(".")
  }
}
