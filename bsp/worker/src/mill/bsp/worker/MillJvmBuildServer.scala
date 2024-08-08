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
import mill.{task, T}
import mill.bsp.worker.Utils.sanitizeUri
import mill.scalalib.JavaModule

import java.util.concurrent.CompletableFuture
import scala.jdk.CollectionConverters._

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
        case m: JavaModule =>
          task.anon {
            (
              m.runClasspath(),
              m.forkArgs(),
              m.forkWorkingDir(),
              m.forkEnv(),
              m.mainClass(),
              m.zincWorker().worker(),
              m.compile()
            )
          }
      }
    ) {
      // We ignore all non-JavaModule
      case (
            ev,
            state,
            id,
            _: JavaModule,
            (runClasspath, forkArgs, forkWorkingDir, forkEnv, mainClass, zincWorker, compile)
          ) =>
        val classpath = runClasspath.map(_.path).map(sanitizeUri)
        val item = new JvmEnvironmentItem(
          id,
          classpath.iterator.toSeq.asJava,
          forkArgs.asJava,
          forkWorkingDir.toString(),
          forkEnv.asJava
        )

        val classes = mainClass.toList ++ zincWorker.discoverMainClasses(compile)
        item.setMainClasses(classes.map(new JvmMainClass(_, Nil.asJava)).asJava)
        item
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
        val pathResolver = ev.pathsResolver

        new JvmCompileClasspathItem(
          id,
          compileClasspath.iterator
            .map(_.resolve(pathResolver))
            .map(sanitizeUri).toSeq.asJava
        )
    } {
      new JvmCompileClasspathResult(_)
    }
}
