package mill.jvmlib.worker

import mill.api.shared.internal.{Problem, ProblemPosition, Severity, internal}
import mill.api.shared.internal.DiagnosticCode
import scala.jdk.OptionConverters._

@internal
class ZincProblem(base: xsbti.Problem) extends Problem {
  override def category: String = base.category()

  override def severity: Severity = base.severity() match {
    case xsbti.Severity.Info => mill.api.shared.internal.Info
    case xsbti.Severity.Warn => mill.api.shared.internal.Warn
    case xsbti.Severity.Error => mill.api.shared.internal.Error
  }

  override def message: String = base.message()

  override def position: ProblemPosition = new ZincProblemPosition(base.position())

  override def diagnosticCode: Option[DiagnosticCode] =
    base.diagnosticCode().toScala.map(ZincDiagnosticCode(_))
}
