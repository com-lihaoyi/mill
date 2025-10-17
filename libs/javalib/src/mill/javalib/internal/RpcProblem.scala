package mill.javalib.internal

import mill.api.daemon.internal.{Problem, Severity}
import mill.api.JsonFormatters.*

/** A [[Problem]] that is sent over RPC. */
case class RpcProblem(
    category: String,
    severity: Severity,
    message: String,
    position: RpcProblemPosition,
    diagnosticCode: Option[RpcDiagnosticCode]
) extends Problem derives upickle.ReadWriter
object RpcProblem {
  def apply(p: Problem): RpcProblem = apply(
    category = p.category,
    severity = p.severity,
    message = p.message,
    position = RpcProblemPosition(p.position),
    diagnosticCode = p.diagnosticCode.map(RpcDiagnosticCode.apply)
  )
}
