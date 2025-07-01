package mill.api.shared.internal.bsp

import mill.api.shared.internal.{ModuleApi, TaskApi}

import java.nio.file.Path

trait BspRunModuleApi extends ModuleApi {

  private[mill] def bspRun(args: Seq[String]): TaskApi[Unit]

  private[mill] def bspJvmRunEnvironment: TaskApi[(
      runClasspath: Seq[Path],
      forkArgs: Seq[String],
      forkWorkingDir: Path,
      forEnv: Map[String, String],
      mainClass: Option[String],
      localMainClasses: Seq[String]
  )]

  private[mill] def bspJvmTestEnvironment: TaskApi[(
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
  )]

}
