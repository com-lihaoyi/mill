package mill.scalalib.bsp

import java.nio.file.Path
import mill.api.internal.bsp.BspJavaModuleApi

import mill.{Args, Task}
import mill.api.internal.{EvaluatorApi, internal}
import mill.define.{Discover, ExternalModule, ModuleCtx}
import mill.scalalib.{JavaModule, ScalaModule, SemanticDbJavaModule}

@internal
object BspJavaModule extends ExternalModule {

  // Requirement of ExternalModule's
  override protected def millDiscover: Discover = Discover[this.type]

  // Hack-ish way to have some BSP state in the module context
  @internal
  implicit class EmbeddableBspJavaModule(jm: JavaModule & BspModule)
      extends mill.define.Module {
    // We act in the context of the module
    override def moduleCtx: ModuleCtx = jm.moduleCtx

    // We keep all BSP-related tasks/state in this sub-module
    @internal
    object internalBspJavaModule extends mill.define.Module with BspJavaModuleApi {

      private[mill] def bspRun(args: Seq[String]): Task[Unit] = Task.Anon {
        jm.run(Task.Anon(Args(args)))
      }

      override private[mill] def bspBuildTargetInverseSources[T](
          id: T,
          searched: String
      ): Task[Seq[T]] =
        Task.Anon {
          val src = jm.allSourceFiles()
          val found = src.map(jm.sanitizeUri).contains(searched)
          if (found) Seq(id) else Seq()
        }

      private[mill] override def bspBuildTargetJavacOptions(
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
