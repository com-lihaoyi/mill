package mill.api.internal.bsp

import java.nio.file.Path

import mill.api.internal.{ModuleApi, TaskApi}

trait BspRunModuleApi extends ModuleApi {

  private[mill] def bspRun(args: Seq[String]): TaskApi[Unit]

  private[mill] def bspJvmRunTestEnvironment: TaskApi[(
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
  )]

}
