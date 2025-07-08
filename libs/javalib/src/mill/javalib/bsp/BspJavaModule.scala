package mill.javalib.bsp

import java.nio.file.Path

import mill.api.daemon.internal.bsp.BspJavaModuleApi
import mill.Task
import mill.api.daemon.internal.{EvaluatorApi, TaskApi, internal}
import mill.api.{Discover, ExternalModule, ModuleCtx}
import mill.javalib.{JavaModule, SemanticDbJavaModule}
import mill.api.JsonFormatters.given

trait BspJavaModule extends mill.api.Module with BspJavaModuleApi {

  def javaModuleRef: mill.api.ModuleRef[JavaModule & BspModule]
  val jm = javaModuleRef()
  override private[mill] def bspBuildTargetInverseSources[T](
      id: T,
      searched: String
  ): Task[Seq[T]] =
    Task.Anon {
      val src = jm.allSourceFiles()
      val found = src.map(jm.sanitizeUri).contains(searched)
      if (found) Seq(id) else Seq()
    }

  override private[mill] def bspBuildTargetJavacOptions(
      needsToMergeResourcesIntoCompileDest: Boolean,
      clientWantsSemanticDb: Boolean
  ): Task[EvaluatorApi => (
      classesPath: Path,
      javacOptions: Seq[String],
      classpath: Seq[String]
  )] = {
    val classesPathTask = jm match {
      case sem: SemanticDbJavaModule if clientWantsSemanticDb =>
        sem.bspCompiledClassesAndSemanticDbFiles
      case m => m.bspCompileClassesPath(needsToMergeResourcesIntoCompileDest)
    }
    Task.Anon { (ev: EvaluatorApi) =>
      (
        classesPathTask().resolve(os.Path(ev.outPathJava)).toNIO,
        jm.javacOptions() ++ jm.mandatoryJavacOptions(),
        jm.bspCompileClasspath(needsToMergeResourcesIntoCompileDest).apply().apply(ev)
      )
    }
  }

  override private[mill] def bspBuildTargetDependencySources
      : Task.Simple[(
          resolvedDepsSources: Seq[Path],
          unmanagedClasspath: Seq[Path]
      )] = Task {
    (
      resolvedDepsSources = jm.millResolver().classpath(
        Seq(
          jm.coursierDependency.withConfiguration(coursier.core.Configuration.provided),
          jm.coursierDependency
        ),
        sources = true
      ).map(_.path.toNIO),
      unmanagedClasspath = jm.unmanagedClasspath().map(_.path.toNIO)
    )
  }

  override private[mill] def bspBuildTargetDependencyModules
      : Task.Simple[(
          mvnDeps: Seq[(String, String, String)],
          unmanagedClasspath: Seq[Path]
      )] = Task {
    (
      // full list of dependencies, including transitive ones
      jm.millResolver()
        .resolution(
          Seq(
            jm.coursierDependency.withConfiguration(coursier.core.Configuration.provided),
            jm.coursierDependency
          )
        )
        .orderedDependencies
        .map { d => (d.module.organization.value, d.module.repr, d.version) },
      jm.unmanagedClasspath().map(_.path.toNIO)
    )
  }

  override private[mill] def bspBuildTargetSources
      : Task.Simple[(sources: Seq[Path], generatedSources: Seq[Path])] =
    Task {
      (jm.sources().map(_.path.toNIO), jm.generatedSources().map(_.path.toNIO))
    }

  override private[mill] def bspBuildTargetResources = Task.Anon {
    jm.resources().map(_.path.toNIO)
  }

  def scalacOptionsTask = Task.Anon(Seq.empty[String])

  override private[mill] def bspBuildTargetScalacOptions(
      needsToMergeResourcesIntoCompileDest: Boolean,
      enableJvmCompileClasspathProvider: Boolean,
      clientWantsSemanticDb: Boolean
  ): Task[(Seq[String], EvaluatorApi => Seq[String], EvaluatorApi => java.nio.file.Path)] = {

    val compileClasspathTask: Task[EvaluatorApi => Seq[String]] =
      if (enableJvmCompileClasspathProvider) {
        // We have a dedicated request for it
        Task.Anon {
          (_: EvaluatorApi) => Seq.empty[String]
        }
      } else {
        jm.bspCompileClasspath(needsToMergeResourcesIntoCompileDest)
      }

    val classesPathTask =
      if (clientWantsSemanticDb) {
        Task.Anon((e: EvaluatorApi) =>
          jm.bspCompiledClassesAndSemanticDbFiles().resolve(os.Path(e.outPathJava)).toNIO
        )
      } else {
        Task.Anon((e: EvaluatorApi) =>
          jm.bspCompileClassesPath(
            needsToMergeResourcesIntoCompileDest
          )().resolve(os.Path(e.outPathJava)).toNIO
        )
      }

    Task.Anon {
      (scalacOptionsTask(), compileClasspathTask(), classesPathTask())
    }
  }

  override private[mill] def bspBuildTargetScalaMainClasses
      : Task.Simple[(
          classes: Seq[String],
          forkArgs: Seq[String],
          forkEnv: Map[String, String]
      )] =
    Task {
      (jm.allLocalMainClasses(), jm.forkArgs(), jm.allForkEnv())
    }

  override private[mill] def bspLoggingTest = Task.Anon {
    System.out.println("bspLoggingTest from System.out")
    System.err.println("bspLoggingTest from System.err")
    Console.out.println("bspLoggingTest from Console.out")
    Console.err.println("bspLoggingTest from Console.err")
    Task.log.info("bspLoggingTest from Task.log.info")
  }

}

object BspJavaModule {
  trait Wrap(jm0: JavaModule & BspModule) extends mill.api.Module {
    override def moduleCtx: ModuleCtx = jm0.moduleCtx

    @internal
    object internalBspJavaModule extends BspJavaModule {
      def javaModuleRef = mill.api.ModuleRef(jm0)
    }
  }

}
