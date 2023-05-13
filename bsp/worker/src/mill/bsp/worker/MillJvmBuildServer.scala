package mill.bsp.worker

import ch.epfl.scala.bsp4j.{
  BuildTargetIdentifier,
  JvmBuildServer,
  JvmEnvironmentItem,
  JvmMainClass,
  JvmRunEnvironmentParams,
  JvmRunEnvironmentResult,
  JvmTestEnvironmentParams,
  JvmTestEnvironmentResult
}
import mill.T
import mill.api.internal
import mill.bsp.worker.Utils.sanitizeUri
import mill.define.Task
import mill.scalalib.JavaModule
import mill.scalalib.bsp.BspModule

import java.util.concurrent.CompletableFuture
import scala.jdk.CollectionConverters._
import scala.util.chaining.scalaUtilChainingOps

@internal
trait MillJvmBuildServer extends JvmBuildServer { this: MillBuildServer =>
  override def jvmRunEnvironment(params: JvmRunEnvironmentParams)
      : CompletableFuture[JvmRunEnvironmentResult] = {
    jvmRunTestEnvironment(
      s"jvmRunEnvironment ${params}",
      params.getTargets.asScala.toSeq,
      (items: Seq[JvmEnvironmentItem]) => new JvmRunEnvironmentResult(items.asJava)
    )
  }

  override def jvmTestEnvironment(params: JvmTestEnvironmentParams)
      : CompletableFuture[JvmTestEnvironmentResult] = {
    jvmRunTestEnvironment(
      s"jvmTestEnvironment ${params}",
      params.getTargets.asScala.toSeq,
      (items: Seq[JvmEnvironmentItem]) => new JvmTestEnvironmentResult(items.asJava)
    )
  }

  def jvmRunTestEnvironment[V](
      name: String,
      targetIds: Seq[BuildTargetIdentifier],
      agg: Seq[JvmEnvironmentItem] => V
  ) = {
    completableTasks(
      name,
      targetIds = _ => targetIds,
      agg = agg,
      tasks = {
        case m: JavaModule =>
          T.task {
            (
              m.runClasspath(),
              m.forkArgs(),
              m.forkWorkingDir(),
              m.forkEnv(),
              m.mainClass(),
              m.zincWorker.worker(),
              m.compile()
            )
          }
      }
    ) {
      case (
            state,
            id,
            m: JavaModule,
            (runClasspath, forkArgs, forkWorkingDir, forkEnv, mainClass, zincWorker, compile)
          ) =>
        val classpath = runClasspath.map(_.path).map(sanitizeUri)
        new JvmEnvironmentItem(
          id,
          classpath.iterator.toSeq.asJava,
          forkArgs.asJava,
          forkWorkingDir.toString(),
          forkEnv.asJava
        ).tap { item =>
          val classes =
            mainClass.toList ++ zincWorker.discoverMainClasses(compile)
          item.setMainClasses(
            classes.map(new JvmMainClass(_, Nil.asJava)).asJava
          )
        }
    }
  }
}
