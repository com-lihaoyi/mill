package mill.init

import scala.util.control.NoStackTrace

@mill.api.experimental
private[mill] case class BuildGenException(message: String) extends Exception(message)
    with NoStackTrace
