package mill.api.daemon.internal.bsp

import mill.api.daemon.internal.{ModuleApi, OptApi, OptsApi, TaskApi}

import java.nio.file.Path

trait BspRunModuleApi extends ModuleApi {

  private[mill] def bspRun(args: Seq[String]): TaskApi[Unit]

  private[mill] def bspJvmRunEnvironment: TaskApi[(
      runClasspath: Seq[Path],
      forkArgs: OptsApi,
      forkWorkingDir: Path,
      forkEnv: Map[String, OptApi],
      mainClass: Option[String],
      localMainClasses: Seq[String]
  )]

  private[mill] def bspJvmTestEnvironment: TaskApi[(
      runClasspath: Seq[Path],
      forkArgs: OptsApi,
      forkWorkingDir: Path,
      forkEnv: Map[String, OptApi],
      mainClass: Option[String],
      testEnvVars: Option[(
          mainClass: String,
          testRunnerClasspathArg: String,
          argsFile: String,
          classpath: Seq[Path]
      )]
  )]

}
