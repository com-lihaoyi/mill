package mill.scalalib.bsp

import java.nio.file.Path

import mill.api.internal.bsp.BspRunModuleApi
import mill.api.internal.internal
import mill.define.{Discover, ExternalModule, ModuleCtx}
import mill.define.JsonFormatters.given
import mill.scalalib.{JavaModule, RunModule, TestModule}
import mill.{Args, Task}

@internal
private[mill] object BspRunModule extends ExternalModule {

  // Requirement of ExternalModule's
  override protected def millDiscover: Discover = Discover[this.type]

  // Hack-ish way to have some BSP state in the module context
  @internal
  implicit class EmbeddableBspRunModule(runModule: RunModule)
      extends mill.define.Module {
    // We act in the context of the module
    override def moduleCtx: ModuleCtx = runModule.moduleCtx

    // We keep all BSP-related tasks/state in this sub-module
    @internal
    object internalBspRunModule extends mill.define.Module with BspRunModuleApi {

      override private[mill] def bspRun(args: Seq[String]): Task[Unit] = Task.Anon {
        runModule.run(Task.Anon(Args(args)))()
      }

      override private[mill] def bspJvmRunTestEnvironment: Task.Simple[(
          runClasspath: Seq[Path],
          forkArgs: Seq[String],
          forkWorkingDir: Path,
          forkEnv: Map[String, String],
          mainClass: Option[String],
          localMainClasses: Option[Seq[String]],
          testEnvVars: Option[(
              mainClass: String,
              testRunnerClasspathArg: String,
              argsFile: String,
              classpath: Seq[Path]
          )]
      )] = {
        val (localMainClasses, testEnvVars) = runModule match {
          case m: (TestModule & JavaModule) =>
            (
              Task.Anon { None },
              Task.Anon { Some(m.getTestEnvironmentVars()()) }
            )
          case _ =>
            (
              Task.Anon { Some(runModule.allLocalMainClasses()) },
              Task.Anon { None }
            )
        }
        Task {
          (
            runModule.runClasspath().map(_.path.toNIO),
            runModule.forkArgs(),
            runModule.forkWorkingDir().toNIO,
            runModule.allForkEnv(),
            runModule.mainClass(),
            localMainClasses(),
            testEnvVars()
          )
        }
      }
    }
  }

}
