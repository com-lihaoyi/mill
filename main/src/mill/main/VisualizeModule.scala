package mill.main

import ammonite.ops.Path
import coursier.Cache
import coursier.core.Repository
import coursier.maven.MavenRepository
import mill.T
import mill.define.{Discover, ExternalModule}
import mill.eval.{Evaluator, PathRef, Result}

object VisualizeModule extends ExternalModule with VisualizeModule {
  def repositories = Seq(
    Cache.ivy2Local,
    MavenRepository("https://repo1.maven.org/maven2"),
    MavenRepository("https://oss.sonatype.org/content/repositories/releases")
  )

  implicit def millScoptEvaluatorReads[T] = new mill.main.EvaluatorScopt[T]()
  lazy val millDiscover = Discover[this.type]
}
trait VisualizeModule extends mill.define.TaskModule{
  def repositories: Seq[Repository]
  def defaultCommandName() = "run"
  def classpath = T{
    mill.modules.Util.millProjectModule("MILL_GRAPHVIZ", "mill-main-graphviz", repositories)
  }
  /**
    * Given a set of tasks, prints out the execution plan of what tasks will be
    * executed in what order, without actually executing them.
    */
  def run(evaluator: Evaluator[Any], targets: String*) = mill.T.command{
    val resolved = RunScript.resolveTasks(
      mill.main.ResolveTasks, evaluator, targets, multiSelect = true
    )
    resolved match{
      case Left(err) => Result.Failure(err)
      case Right(rs) =>
        Result.Success(
          mill.modules.Jvm.inprocess(classpath().map(_.path), false, isolated = false, cl => {
            cl.loadClass("mill.main.graphviz.GraphvizTools")
              .getMethod("apply", classOf[Seq[_]], classOf[Path])
              .invoke(null, rs, T.ctx().dest)
              .asInstanceOf[Seq[PathRef]]
          })
        )

    }
  }

}
