package mill.javalib.internal

import mill.api.daemon.internal.DiagnosticCode

/** A [[DiagnosticCode]] that is sent over RPC. */
case class RpcDiagnosticCode(
    code: String,
    explanation: Option[String]
) extends DiagnosticCode derives upickle.ReadWriter
object RpcDiagnosticCode {
  def apply(d: DiagnosticCode): RpcDiagnosticCode = apply(
    code = d.code,
    explanation = d.explanation
  )
}
