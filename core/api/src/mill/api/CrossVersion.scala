package mill.api
import upickle.default.{macroRW, ReadWriter as RW}

sealed trait CrossVersion {
  import CrossVersion._

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
      case cross: Binary =>
        s"${firstSuffix}_${binaryVersion}"
      case cross: Full =>
        s"${firstSuffix}_${fullVersion}"
    }
    require(!suffix.contains("/"), "Artifact suffix must not contain `/`s")
    suffix
  }
}
object CrossVersion {
  case class Constant(value: String, platformed: Boolean) extends CrossVersion
  object Constant {
    implicit def rw: RW[Constant] = macroRW
  }
  case class Binary(platformed: Boolean) extends CrossVersion
  object Binary {
    implicit def rw: RW[Binary] = macroRW
  }
  case class Full(platformed: Boolean) extends CrossVersion
  object Full {
    implicit def rw: RW[Full] = macroRW
  }

  def empty(platformed: Boolean): Constant = Constant(value = "", platformed)

  implicit def rw: RW[CrossVersion] = RW.merge(Constant.rw, Binary.rw, Full.rw)
}
