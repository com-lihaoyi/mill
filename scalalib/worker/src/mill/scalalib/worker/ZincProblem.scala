package mill.scalalib.worker

import mill.runner.api.{Problem, ProblemPosition, Severity}
import mill.runner.api.DiagnosticCode
import mill.api.internal
import scala.jdk.OptionConverters._

@internal
class ZincProblem(base: xsbti.Problem) extends Problem {
  override def category: String = base.category()

  override def severity: Severity = base.severity() match {
    case xsbti.Severity.Info => mill.runner.api.Info
    case xsbti.Severity.Warn => mill.runner.api.Warn
    case xsbti.Severity.Error => mill.runner.api.Error
  }

  override def message: String = base.message()

  override def position: ProblemPosition = new ZincProblemPosition(base.position())

  override def diagnosticCode: Option[DiagnosticCode] =
    base.diagnosticCode().toScala.map(ZincDiagnosticCode(_))
}
