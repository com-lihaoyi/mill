package mill.init

import scala.util.control.NoStackTrace

@mill.api.experimental
case class BuildGenException(message: String) extends Exception(message) with NoStackTrace
