package mill.api.daemon

import scala.util.control.ControlThrowable

/**
 * Exception thrown to stop the RPC server loop while still sending a response.
 * This allows a controlled shutdown where the client receives a proper response
 * before the server stops processing requests.
 *
 * Extends ControlThrowable so it bypasses NonFatal catches in task evaluation.
 */
class StopWithResponse[R](val response: R) extends ControlThrowable
