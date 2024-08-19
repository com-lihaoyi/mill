package mill.contrib.jacoco

import mill.api.PathRef
import mill.define.{Command, Input, NamedTask, Task}
import mill.api.Result
import mill.modules.Jvm
import mill.scalalib.api.CompilationResult
import mill.T
import os.Path

trait JacocoReportModule extends JacocoReportModulePlatform { jacocoReportModule =>

  /** Location where collected coverage data is stored. */
  def jacocoDataDir: T[PathRef] = T.persistent { PathRef(T.dest) }

  def forkArgs: T[Seq[String]] = Seq[String]()

  def jacocoCliTask(args: Task[Seq[String]]): Task[PathRef] = T.task {
    Jvm.runSubprocess(
      mainClass = "org.jacoco.cli.internal.Main",
      classPath = jacocoClasspath().map(_.path),
      jvmArgs = forkArgs(),
      envArgs = Map(),
      mainArgs = args(), // .map(_.replaceAll("\\Q$$REPORTDIR$$\\E", T.dest.toIO.getAbsolutePath())),
      workingDir = T.dest
    )
    PathRef(T.dest)
  }

  def jacocoCli(args: String*): Command[Unit] = T.command {
    jacocoCliTask(T.task { args })()
    ()
  }

  def jacocoReportFull(evaluator: mill.eval.Evaluator): Command[PathRef] = T.command {
    jacocoReportTask(
      evaluator = evaluator,
      sources = sourcesSelector,
      compiled = compileSelector,
      excludeSources = excludeSourcesSelector,
      excludeCompiled = excludeCompiledSelector
    )()
  }

  def jacocoReportTask(
      evaluator: mill.eval.Evaluator,
      sources: String,
      excludeSources: String,
      compiled: String = "",
      excludeCompiled: String = ""
  ): Task[PathRef] = {

    val sourcesTasks: Seq[Task[Seq[PathRef]]] = resolveTasks(sources, evaluator)
    val excludeSourcesTasks: Seq[Task[Seq[PathRef]]] = resolveTasks(excludeSources, evaluator)
    val compiledTasks: Seq[Task[CompilationResult]] = resolveTasks(compiled, evaluator)
    val excludeCompiledTasks: Seq[Task[CompilationResult]] = resolveTasks(excludeCompiled, evaluator)

    jacocoCliTask(
      T.task {
        val sourcePaths: Seq[Path] =
          T.sequence(excludeTasks(sourcesTasks, excludeSourcesTasks))().flatten.map(_.path).filter(os.exists)
        val compiledPaths: Seq[Path] =
          T.sequence(excludeTasks(compiledTasks, excludeCompiledTasks))().map(_.classes.path).filter(os.exists)

        T.log.debug(s"sourcePaths: ${sourcePaths}")
        T.log.debug(s"compiledPaths: ${compiledPaths}")

        val execFile = jacocoDataDir().path / "jacoco.exec"
        if (os.exists(execFile)) {
          val res = Seq(
            "report",
            s"${jacocoDataDir().path}/jacoco.exec",
            "--html",
            s"${T.dest / "html"}",
            "--xml",
            s"${T.dest / "jacoco.xml"}"
          ) ++
            sourcePaths.flatMap(p => Seq("--sourcefiles", p.toIO.getAbsolutePath)) ++
            compiledPaths.flatMap(p => Seq("--classfiles", p.toIO.getAbsolutePath))

          Result.Success(res)
        } else {
          Result.Failure("Cannot find JaCoCo datafile. Did you forget to run any test?")
        }
      }
    )
  }

  private def excludeTasks[T](orig: Seq[Task[T]], exclusions: Seq[Task[T]]): Seq[Task[T]] = {
    val excludeLabels = exclusions.collect { case x: NamedTask[T] =>
      x.toString
    }
    orig.flatMap {
      case t: NamedTask[T] if excludeLabels.contains(t.toString) => Seq()
      case x => Seq(x)
    }
  }

}
