package mill.scalalib.bsp

import java.nio.file.Path

import mill.api.shared.internal.bsp.BspJavaModuleApi
import mill.Task
import mill.api.shared.internal.{EvaluatorApi, TaskApi, internal}
import mill.api.{Discover, ExternalModule, ModuleCtx}
import mill.scalalib.{JavaModule, ScalaModule, SemanticDbJavaModule}
import mill.api.JsonFormatters.given

@internal
private[mill] object BspJavaModule extends ExternalModule {

  // Requirement of ExternalModule's
  override protected def millDiscover: Discover = Discover[this.type]

  // Hack-ish way to have some BSP state in the module context
  @internal
  implicit class EmbeddableBspJavaModule(jm: JavaModule & BspModule)
      extends mill.api.Module {
    // We act in the context of the module
    override def moduleCtx: ModuleCtx = jm.moduleCtx

    // We keep all BSP-related tasks/state in this sub-module
    @internal
    object internalBspJavaModule extends mill.api.Module with BspJavaModuleApi {

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

      override private[mill] def bspBuildTargetScalacOptions(
          needsToMergeResourcesIntoCompileDest: Boolean,
          enableJvmCompileClasspathProvider: Boolean,
          clientWantsSemanticDb: Boolean
      ): Task[(Seq[String], EvaluatorApi => Seq[String], EvaluatorApi => java.nio.file.Path)] = {
        val scalacOptionsTask = jm match {
          case m: ScalaModule => m.allScalacOptions
          case _ => Task.Anon {
              Seq.empty[String]
            }
        }

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
  }

}
