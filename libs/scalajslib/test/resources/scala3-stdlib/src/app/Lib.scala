package app

object Lib {
  // Since Scala 3.9, `Option.orNull` takes no implicit evidence: the call only links
  // against the Scala 3 standard library, not against the Scala 2.13 one
  def nameOrNull(name: Option[String]): String = name.orNull
}
