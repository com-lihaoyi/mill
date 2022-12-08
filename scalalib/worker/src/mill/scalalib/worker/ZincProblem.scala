package mill.scalalib.worker

import mill.api.{Problem, ProblemPosition, Severity, internal}
import mill.api.DiagnosticCode
import scala.jdk.OptionConverters._

@internal
class ZincProblem(base: xsbti.Problem) extends Problem {
  override def category: String = base.category()

  override def severity: Severity = base.severity() match {
    case xsbti.Severity.Info => mill.api.Info
    case xsbti.Severity.Warn => mill.api.Warn
    case xsbti.Severity.Error => mill.api.Error
  }

  override def message: String = base.message()

  override def position: ProblemPosition = new ZincProblemPosition(base.position())

  override def diagnosticCode: Option[DiagnosticCode] =
    base.diagnosticCode().toScala.map(ZincDiagnosticCode)
}
