package mill.scalalib.worker

import mill.api.internal
import mill.runner.api.DiagnosticCode

import scala.jdk.OptionConverters._

@internal
final case class ZincDiagnosticCode(base: xsbti.DiagnosticCode) extends DiagnosticCode {
  override def code: String = base.code()
  override def explanation: Option[String] = base.explanation().toScala
}
