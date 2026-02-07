package mill.javalib.bsp

import java.nio.file.Path

import mill.api.daemon.internal.bsp.BspRunModuleApi
import mill.api.daemon.internal.internal
import mill.api.ModuleCtx
import mill.api.JsonFormatters.given
import mill.javalib.{JavaModule, RunModule, TestModule}
import mill.{Args, Task}

@internal
private[mill] trait BspRunModule(runModule: RunModule) extends mill.api.Module {
  override def moduleCtx: ModuleCtx = runModule.moduleCtx

  override implicit def moduleNestedCtx: ModuleCtx.Nested = runModule.moduleNestedCtx

  object internalBspRunModule extends mill.api.Module with BspRunModuleApi {
    override private[mill] def bspRun(args: Seq[String]): Task[Unit] =
      runModule.run(Task.Anon(Args(args)))

    override private[mill] def bspJvmRunEnvironment: Task.Simple[(
        runClasspath: Seq[Path],
        forkArgs: Seq[String],
        forkWorkingDir: Path,
        forkEnv: Map[String, String],
        mainClass: Option[String],
        localMainClasses: Seq[String]
    )] =
      Task {
        (
          runModule.runClasspath().map(_.path.toNIO),
          runModule.forkArgs(),
          runModule.forkWorkingDir().toNIO,
          runModule.allForkEnv(),
          runModule.mainClass(),
          runModule.allLocalMainClasses()
        )
      }

    override private[mill] def bspJvmTestEnvironment: Task.Simple[(
        runClasspath: Seq[Path],
        forkArgs: Seq[String],
        forkWorkingDir: Path,
        forkEnv: Map[String, String],
        mainClass: Option[String],
        testEnvVars: Option[(
            mainClass: String,
            testRunnerClasspathArg: String,
            argsFile: String,
            classpath: Seq[Path]
        )]
    )] = {
      val testEnvVars = runModule match {
        case m: (TestModule & JavaModule) =>
          Task.Anon {
            Some(m.getTestEnvironmentVars()())
          }
        case _ =>
          Task.Anon {
            None
          }
      }
      Task {
        (
          runModule.runClasspath().map(_.path.toNIO),
          runModule.forkArgs(),
          runModule.forkWorkingDir().toNIO,
          runModule.allForkEnv(),
          runModule.mainClass(),
          testEnvVars()
        )
      }
    }
  }
}
