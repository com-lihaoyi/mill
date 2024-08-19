package mill.contrib.jacoco

import mill.define.{Input, Target}
import mill.scalalib.{Dep, DepSyntax, JavaModule, TestModule}
import mill.{Agg, T}

trait JacocoTestModule extends JavaModule with TestModule {

  def jacocoReportModule: JacocoReportModule = Jacoco

  def jacocoAgentDep: Target[Agg[Dep]] = T {
    Agg(ivy"org.jacoco:org.jacoco.agent:${jacocoReportModule.jacocoVersion()}")
  }

  /**
   * Add the Jacoco Agent to the runtime dependencies.
   */
  override def runIvyDeps: Target[Agg[Dep]] = T {
    super.runIvyDeps() ++ jacocoAgentDep().filter(_ => jacocoEnabled())
  }

  /**
   * If `true`, enable the jacoco report agent.
   * Defaults to `true` but support the existence of environment variable `JACOCO_DISABLED`.
   */
  def jacocoEnabled: Input[Boolean] = T.input {
    !T.env.contains("JACOCO_DISABLED")
  }

  /**
   * Add Jacoco specific javaagent options.
   */
  override def forkArgs: Target[Seq[String]] = super.forkArgs() ++ {
    Seq(
      s"-javaagent:${jacocoReportModule.jacocoAgentJar().path}=destfile=${jacocoReportModule.jacocoDataDir().path}/jacoco.exec"
    ).filter(_ => jacocoEnabled())
  }
}
