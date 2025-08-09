package mill.javalib.zinc

import mill.api.daemon.internal.{DiagnosticCode, internal}

import scala.jdk.OptionConverters.*

@internal
final case class ZincDiagnosticCode(base: xsbti.DiagnosticCode) extends DiagnosticCode {
  override def code: String = base.code()
  override def explanation: Option[String] = base.explanation().toScala
}
