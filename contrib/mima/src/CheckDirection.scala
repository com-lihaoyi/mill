package mill.mima

import upickle.default._

sealed trait CheckDirection extends Product with Serializable
object CheckDirection {
  case object Backward extends CheckDirection
  case object Forward extends CheckDirection
  case object Both extends CheckDirection

  implicit val backwardRW: ReadWriter[CheckDirection.Backward.type] = macroRW
  implicit val bothRW: ReadWriter[CheckDirection.Both.type] = macroRW
  implicit val forwardRW: ReadWriter[CheckDirection.Forward.type] = macroRW
  implicit val checkDirectionRW: ReadWriter[CheckDirection] = macroRW
}
