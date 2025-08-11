package mill.javalib.worker

import mill.api.daemon.internal.{DiagnosticCode, internal}

import scala.jdk.OptionConverters._

@internal
final case class ZincDiagnosticCode(base: xsbti.DiagnosticCode) extends DiagnosticCode {
  override def code: String = base.code()
  override def explanation: Option[String] = base.explanation().toScala
}
