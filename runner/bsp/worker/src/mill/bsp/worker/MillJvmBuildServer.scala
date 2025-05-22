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
import mill.api.internal.{TaskApi, JavaModuleApi, RunModuleApi, TestModuleApi}
import mill.bsp.worker.Utils.sanitizeUri
import java.util.concurrent.CompletableFuture

import scala.jdk.CollectionConverters.*

private trait MillJvmBuildServer extends JvmBuildServer { this: MillBuildServer =>

  override def buildTargetJvmRunEnvironment(params: JvmRunEnvironmentParams)
      : CompletableFuture[JvmRunEnvironmentResult] = {
    jvmRunTestEnvironment(
      params.getTargets.asScala,
      new JvmRunEnvironmentResult(_)
    )
  }

  override def buildTargetJvmTestEnvironment(params: JvmTestEnvironmentParams)
      : CompletableFuture[JvmTestEnvironmentResult] = {
    jvmRunTestEnvironment(
      params.getTargets.asScala,
      new JvmTestEnvironmentResult(_)
    )
  }

  def jvmRunTestEnvironment[V](
      targetIds: collection.Seq[BuildTargetIdentifier],
      agg: java.util.List[JvmEnvironmentItem] => V
  )(implicit name: sourcecode.Name): CompletableFuture[V] = {
    handlerTasks(
      targetIds = _ => targetIds,
      tasks = { case m: RunModuleApi => m.bspJvmRunTestEnvironment },
      requestDescription = "Getting JVM test environment of {}"
    ) {
      case (
            ev,
            state,
            id,
            _: (TestModuleApi & JavaModuleApi),
            (
              _,
              forkArgs,
              forkWorkingDir,
              forkEnv,
              _,
              testEnvVars: (String, String, String, Seq[String])
            )
          ) =>
        val (mainClass, testRunnerClassPath, argsFile, classpath) = testEnvVars
        val fullMainArgs: List[String] = List(testRunnerClassPath, argsFile)
        val item = new JvmEnvironmentItem(
          id,
          classpath.asJava,
          forkArgs.asJava,
          forkWorkingDir.toString(),
          forkEnv.asJava
        )
        item.setMainClasses(List(mainClass).map(new JvmMainClass(_, fullMainArgs.asJava)).asJava)
        item
      case (
            ev,
            state,
            id,
            _: RunModuleApi,
            (
              runClasspath,
              forkArgs,
              forkWorkingDir,
              forkEnv,
              mainClass,
              localMainClasses: Seq[String]
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
      case _ => ???
    } {
      agg
    }
  }

  override def buildTargetJvmCompileClasspath(params: JvmCompileClasspathParams)
      : CompletableFuture[JvmCompileClasspathResult] =
    handlerTasks(
      targetIds = _ => params.getTargets.asScala,
      tasks = {
        case m: JavaModuleApi => m.bspCompileClasspath
      },
      requestDescription = "Getting JVM compile class path of {}"
    ) {
      case (ev, _, id, _: JavaModuleApi, compileClasspath) =>
        new JvmCompileClasspathItem(id, compileClasspath(ev).asJava)
      case _ => ???
    } {
      new JvmCompileClasspathResult(_)
    }
}
