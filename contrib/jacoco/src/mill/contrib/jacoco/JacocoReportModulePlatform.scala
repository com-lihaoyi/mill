package mill.contrib.jacoco

import mill.{Agg, T}
import mill.api.PathRef
import mill.define.Task
import mill.eval.Evaluator
import mill.resolve.{Resolve, SelectMode}
import mill.scalalib.{CoursierModule, DepSyntax}
import mill.util.Version

trait JacocoReportModulePlatform extends CoursierModule {

  /** The Jacoco Version. */
  def jacocoVersion: T[String]

  /** The Jacoco Classpath contains the tools used to generate reports from collected coverage data. */
  def jacocoClasspath: T[Agg[PathRef]] = T {
    defaultResolver().resolveDeps(
      Seq(ivy"org.jacoco:org.jacoco.cli:${jacocoVersion()}")
    )
  }

  /** The Jacoco Agent is used at test-runtime. */
  def jacocoAgentJar: T[PathRef] = T {
    val jars = defaultResolver().resolveDeps(
      Seq(ivy"org.jacoco:org.jacoco.agent:${jacocoVersion()};classifier=runtime".exclude("*" -> "*"))
    )
    jars.iterator.next()
  }

  protected[jacoco] def resolveTasks[T](tasks: String, evaluator: Evaluator): Seq[Task[T]] = {
    if (tasks.trim().isEmpty()) Seq()
    else Resolve.Tasks.resolve(evaluator.rootModule, Seq(tasks), SelectMode.Multi) match {
      case Left(err) => throw new Exception(err)
      case Right(tasks) => tasks.asInstanceOf[Seq[Task[T]]]
    }
  }

  protected[jacoco] val (sourcesSelector, compileSelector, excludeSourcesSelector, excludeCompiledSelector) = {
    // since version 0.11.7 Mill supports type selectors in target queries
    // https://github.com/com-lihaoyi/mill/pull/2997
    if (
      Version.parse(mill.api.BuildInfo.millVersion).isAtLeast(Version.parse("0.11.7"))(
        mill.util.Version.IgnoreQualifierOrdering
      )
    ) ("__:JavaModule:^TestModule.allSources", "__:JavaModule:^TestModule.compile", "", "")
      else
      ("__.allSources", "__.compile", "__.test.allSources", "__.test.compile")
  }

}
