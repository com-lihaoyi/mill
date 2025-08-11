package mill.api

/**
 * Models the different kinds of cross-versions supported by Mill for Scala dependencies.
 */
enum CrossVersion derives upickle.default.ReadWriter {
  import CrossVersion.*

  /** If true, the cross-version suffix should start with a platform suffix if it exists */
  def platformed: Boolean

  def isBinary: Boolean =
    this.isInstanceOf[Binary]
  def isConstant: Boolean =
    this.isInstanceOf[Constant]
  def isFull: Boolean =
    this.isInstanceOf[Full]

  /** The string that should be appended to the module name to get the artifact name */
  def suffixString(binaryVersion: String, fullVersion: String, platformSuffix: String): String = {
    val firstSuffix = if (platformed) platformSuffix else ""
    val suffix = this match {
      case cross: Constant =>
        s"${firstSuffix}${cross.value}"
      case _: Binary =>
        s"${firstSuffix}_${binaryVersion}"
      case _: Full =>
        s"${firstSuffix}_${fullVersion}"
    }
    require(!suffix.contains("/"), "Artifact suffix must not contain `/`s")
    suffix
  }

  case Constant(value: String, platformed: Boolean)

  case Binary(platformed: Boolean)

  case Full(platformed: Boolean)

}
object CrossVersion {
  def empty(platformed: Boolean): Constant = Constant(value = "", platformed)
}
