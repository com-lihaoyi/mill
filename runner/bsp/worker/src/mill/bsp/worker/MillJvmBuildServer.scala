package mill.bsp.worker

import ch.epfl.scala.bsp4j.{
  BuildTargetIdentifier,
  JvmBuildServer,
  JvmCompileClasspathItem,
  JvmCompileClasspathParams,
  JvmCompileClasspathResult,
  JvmEnvironmentItem,
  JvmMainClass,
  JvmRunEnvironmentParams,
  JvmRunEnvironmentResult,
  JvmTestEnvironmentParams,
  JvmTestEnvironmentResult
}
import mill.api.internal.{JavaModuleApi, RunModuleApi, TestModuleApi}
import mill.bsp.worker.Utils.sanitizeUri
import java.util.concurrent.CompletableFuture

import scala.jdk.CollectionConverters.*

private trait MillJvmBuildServer extends JvmBuildServer { this: MillBuildServer =>

  override def buildTargetJvmRunEnvironment(params: JvmRunEnvironmentParams)
      : CompletableFuture[JvmRunEnvironmentResult] = {
    jvmRunEnvironmentFor(
      params.getTargets.asScala,
      new JvmRunEnvironmentResult(_),
      forTests = false
    )
  }

  override def buildTargetJvmTestEnvironment(params: JvmTestEnvironmentParams)
      : CompletableFuture[JvmTestEnvironmentResult] = {
    jvmRunEnvironmentFor(
      params.getTargets.asScala,
      new JvmTestEnvironmentResult(_),
      forTests = true
    )
  }

  private def jvmRunEnvironmentFor[V](
      targetIds: collection.Seq[BuildTargetIdentifier],
      agg: java.util.List[JvmEnvironmentItem] => V,
      forTests: Boolean
  )(implicit name: sourcecode.Name): CompletableFuture[V] = {
    handlerTasks(
      targetIds = _ => targetIds,
      tasks = { case m: RunModuleApi => m.bspRunModule().bspJvmRunTestEnvironment },
      requestDescription = s"Getting JVM run environment (for tests: $forTests) of {}"
    ) {
      case (
            _,
            _,
            id,
            _: (TestModuleApi & JavaModuleApi),
            (
              _,
              forkArgs,
              forkWorkingDir,
              forkEnv,
              _,
              None,
              Some(testEnvVars)
            )
          ) =>
        val fullMainArgs: List[String] =
          List(testEnvVars.testRunnerClasspathArg, testEnvVars.argsFile)
        val item = new JvmEnvironmentItem(
          id,
          testEnvVars.classpath.asJava,
          forkArgs.asJava,
          forkWorkingDir.toString(),
          forkEnv.asJava
        )
        item.setMainClasses(List(testEnvVars.mainClass).map(new JvmMainClass(
          _,
          fullMainArgs.asJava
        )).asJava)
        item

      case (
            _,
            _,
            id,
            _: RunModuleApi,
            (
              runClasspath,
              forkArgs,
              forkWorkingDir,
              forkEnv,
              mainClass,
              Some(localMainClasses),
              None
            )
          ) =>
        val classpath = runClasspath.map(sanitizeUri)
        val item = new JvmEnvironmentItem(
          id,
          classpath.asJava,
          forkArgs.asJava,
          forkWorkingDir.toString(),
          forkEnv.asJava
        )

        val classes = mainClass.toList ++ localMainClasses
        item.setMainClasses(classes.map(new JvmMainClass(_, Nil.asJava)).asJava)
        item

      case other =>
        throw new NotImplementedError(s"Unsupported target: ${pprint(other).plainText}")
    } {
      agg
    }
  }

  override def buildTargetJvmCompileClasspath(params: JvmCompileClasspathParams)
      : CompletableFuture[JvmCompileClasspathResult] =
    handlerTasks(
      targetIds = _ => params.getTargets.asScala,
      tasks = {
        case m: JavaModuleApi =>
          m.bspCompileClasspath(sessionInfo.clientType.mergeResourcesIntoClasses)
      },
      requestDescription = "Getting JVM compile class path of {}"
    ) {
      case (ev, _, id, _, compileClasspath) =>
        new JvmCompileClasspathItem(id, compileClasspath(ev).asJava)
    } {
      new JvmCompileClasspathResult(_)
    }
}
