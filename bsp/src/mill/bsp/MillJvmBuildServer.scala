package mill.bsp

import ch.epfl.scala.bsp4j.{
  BuildTargetIdentifier,
  JvmBuildServer,
  JvmEnvironmentItem,
  JvmRunEnvironmentParams,
  JvmRunEnvironmentResult,
  JvmTestEnvironmentParams,
  JvmTestEnvironmentResult
}
import mill.T
import mill.define.Task
import mill.scalalib.JavaModule
import mill.scalalib.bsp.BspModule

import java.util.concurrent.CompletableFuture
import scala.jdk.CollectionConverters._

trait MillJvmBuildServer extends JvmBuildServer { this: MillBuildServer =>
  override def jvmRunEnvironment(params: JvmRunEnvironmentParams)
      : CompletableFuture[JvmRunEnvironmentResult] =
    completable(s"jvmRunEnvironment ${params}") { state =>
      targetTasks(
        state,
        targetIds = params.getTargets.asScala.toSeq,
        agg = (items: Seq[JvmEnvironmentItem]) => new JvmRunEnvironmentResult(items.asJava)
      )(taskToJvmEnvironmentItem)
    }

  override def jvmTestEnvironment(params: JvmTestEnvironmentParams)
      : CompletableFuture[JvmTestEnvironmentResult] =
    completable(s"jvmTestEnvironment ${params}") { state =>
      targetTasks(
        state,
        targetIds = params.getTargets.asScala.toSeq,
        agg = (items: Seq[JvmEnvironmentItem]) => new JvmTestEnvironmentResult(items.asJava)
      )(taskToJvmEnvironmentItem)
    }

  private val taskToJvmEnvironmentItem
      : (BuildTargetIdentifier, BspModule) => Task[JvmEnvironmentItem] = {
    case (id, m: JavaModule) =>
      T.task {
        val classpath = m.runClasspath().map(_.path).map(sanitizeUri.apply)
        new JvmEnvironmentItem(
          id,
          classpath.iterator.toSeq.asJava,
          m.forkArgs().asJava,
          m.forkWorkingDir().toString(),
          m.forkEnv().asJava
        )
      }
  }
}
