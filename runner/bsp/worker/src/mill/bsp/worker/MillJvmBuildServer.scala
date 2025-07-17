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
import mill.api.daemon.internal.{JavaModuleApi, RunModuleApi, TestModuleApi}
import mill.bsp.worker.Utils.sanitizeUri
import java.util.concurrent.CompletableFuture

import scala.jdk.CollectionConverters.*

private trait MillJvmBuildServer extends JvmBuildServer { this: MillBuildServer =>

  override def buildTargetJvmRunEnvironment(params: JvmRunEnvironmentParams)
      : CompletableFuture[JvmRunEnvironmentResult] = {
    jvmRunEnvironmentFor(
      params.getTargets.asScala,
      new JvmRunEnvironmentResult(_),
      params.getOriginId
    )
  }

  override def buildTargetJvmTestEnvironment(params: JvmTestEnvironmentParams)
      : CompletableFuture[JvmTestEnvironmentResult] = {
    jvmTestEnvironmentFor(
      params.getTargets.asScala,
      new JvmTestEnvironmentResult(_),
      params.getOriginId
    )
  }

  private def jvmTestEnvironmentFor[V](
      targetIds: collection.Seq[BuildTargetIdentifier],
      agg: java.util.List[JvmEnvironmentItem] => V,
      originId: String
  )(implicit name: sourcecode.Name): CompletableFuture[V] = {
    handlerTasks(
      targetIds = _ => targetIds,
      tasks = { case m: RunModuleApi => m.bspRunModule().bspJvmTestEnvironment },
      requestDescription = "Getting JVM test environment of {}",
      originId = originId
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
              Some(testEnvVars)
            )
          ) =>
        val fullMainArgs: List[String] =
          List(testEnvVars.testRunnerClasspathArg, testEnvVars.argsFile)
        val item = new JvmEnvironmentItem(
          id,
          testEnvVars.classpath.map(sanitizeUri).asJava,
          forkArgs.asJava,
          forkWorkingDir.toString(),
          forkEnv.asJava
        )
        item.setMainClasses(List(testEnvVars.mainClass).map(new JvmMainClass(
          _,
          fullMainArgs.asJava
        )).asJava)
        item

      case other =>
        throw new NotImplementedError(s"Unsupported target: ${pprint(other).plainText}")
    } {
      agg
    }
  }

  private def jvmRunEnvironmentFor[V](
      targetIds: collection.Seq[BuildTargetIdentifier],
      agg: java.util.List[JvmEnvironmentItem] => V,
      originId: String
  )(implicit name: sourcecode.Name): CompletableFuture[V] = {
    handlerTasks(
      targetIds = _ => targetIds,
      tasks = { case m: RunModuleApi => m.bspRunModule().bspJvmRunEnvironment },
      requestDescription = "Getting JVM run environment of {}",
      originId = originId
    ) {
      case (
            _,
            _,
            id,
            _,
            res
          ) =>
        val classpath = res.runClasspath.map(sanitizeUri)
        val item = new JvmEnvironmentItem(
          id,
          classpath.asJava,
          res.forkArgs.asJava,
          res.forkWorkingDir.toString(),
          res.forkEnv.asJava
        )

        val classes = res.mainClass.toList ++ res.localMainClasses
        item.setMainClasses(classes.map(new JvmMainClass(_, Nil.asJava)).asJava)
        item
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
      requestDescription = "Getting JVM compile class path of {}",
      originId = ""
    ) {
      case (ev, _, id, _, compileClasspath) =>
        new JvmCompileClasspathItem(id, compileClasspath(ev).asJava)
    } {
      new JvmCompileClasspathResult(_)
    }
}
