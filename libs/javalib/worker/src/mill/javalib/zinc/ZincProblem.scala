package mill.javalib.zinc

import mill.api.daemon.internal.*

import scala.jdk.OptionConverters.*

@internal
class ZincProblem(base: xsbti.Problem) extends Problem {
  override def category: String = base.category()

  override def severity: Severity = base.severity() match {
    case xsbti.Severity.Info => mill.api.daemon.internal.Info
    case xsbti.Severity.Warn => mill.api.daemon.internal.Warn
    case xsbti.Severity.Error => mill.api.daemon.internal.Error
  }

  override def message: String = base.message()

  override def position: ProblemPosition = ZincProblemPosition(base.position())

  override def diagnosticCode: Option[DiagnosticCode] =
    base.diagnosticCode().toScala.map(ZincDiagnosticCode(_))
}
