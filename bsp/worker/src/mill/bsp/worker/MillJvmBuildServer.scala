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
import mill.Task
import mill.bsp.worker.Utils.sanitizeUri
import mill.scalalib.{JavaModule, RunModule, TestModule}
import java.util.concurrent.CompletableFuture

import scala.jdk.CollectionConverters.*

import mill.api.PathRef

private trait MillJvmBuildServer extends JvmBuildServer { this: MillBuildServer =>

  override def buildTargetJvmRunEnvironment(params: JvmRunEnvironmentParams)
      : CompletableFuture[JvmRunEnvironmentResult] = {
    jvmRunTestEnvironment(
      s"buildTarget/jvmRunEnvironment ${params}",
      params.getTargets.asScala.toSeq,
      new JvmRunEnvironmentResult(_)
    )
  }

  override def buildTargetJvmTestEnvironment(params: JvmTestEnvironmentParams)
      : CompletableFuture[JvmTestEnvironmentResult] = {
    jvmRunTestEnvironment(
      s"buildTarget/jvmTestEnvironment ${params}",
      params.getTargets.asScala.toSeq,
      new JvmTestEnvironmentResult(_)
    )
  }

  def jvmRunTestEnvironment[V](
      name: String,
      targetIds: Seq[BuildTargetIdentifier],
      agg: java.util.List[JvmEnvironmentItem] => V
  ): CompletableFuture[V] = {
    completableTasks(
      name,
      targetIds = _ => targetIds,
      tasks = {
        case m: RunModule =>
          val moduleSpecificTask = m match {
            case m: (TestModule & JavaModule) => m.getTestEnvironmentVars()
            case _ => m.allLocalMainClasses
          }
          Task.Anon {
            (
              m.runClasspath(),
              m.forkArgs(),
              m.forkWorkingDir(),
              m.forkEnv(),
              m.mainClass(),
              moduleSpecificTask()
            )
          }
      }
    ) {
      case (
            ev,
            state,
            id,
            _: (TestModule & JavaModule),
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
            _: RunModule,
            (
              runClasspath,
              forkArgs,
              forkWorkingDir,
              forkEnv,
              mainClass,
              localMainClasses: Seq[String]
            )
          ) =>
        val classpath = runClasspath.map(_.path).map(sanitizeUri)
        val item = new JvmEnvironmentItem(
          id,
          classpath.iterator.toSeq.asJava,
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
    completableTasks(
      hint = "buildTarget/jvmCompileClasspath",
      targetIds = _ => params.getTargets.asScala.toSeq,
      tasks = {
        case m: JavaModule => m.bspCompileClasspath
      }
    ) {
      case (ev, _, id, _: JavaModule, compileClasspath) =>

        new JvmCompileClasspathItem(
          id,
          compileClasspath.iterator
            .map(_.resolve(ev.outPath))
            .map(sanitizeUri).toSeq.asJava
        )
      case _ => ???
    } {
      new JvmCompileClasspathResult(_)
    }
}
