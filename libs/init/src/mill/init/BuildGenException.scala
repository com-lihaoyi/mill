package mill.init

import scala.util.control.NoStackTrace

case class BuildGenException(message: String) extends Exception(message)
    with NoStackTrace
