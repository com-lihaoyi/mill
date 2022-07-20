package mill.bsp

import ch.epfl.scala.bsp4j.{
  JavaBuildServer,
  JavacOptionsItem,
  JavacOptionsParams,
  JavacOptionsResult
}
import mill.T
import mill.scalalib.JavaModule

import java.util.concurrent.CompletableFuture
import scala.jdk.CollectionConverters._

trait MillJavaBuildServer extends JavaBuildServer { this: MillBuildServer =>
  override def buildTargetJavacOptions(javacOptionsParams: JavacOptionsParams)
      : CompletableFuture[JavacOptionsResult] =
    completable(s"buildTargetJavacOptions ${javacOptionsParams}") { state =>
      import state.evaluator
      targetTasks(
        state,
        targetIds = javacOptionsParams.getTargets.asScala.toSeq,
        agg = (items: Seq[JavacOptionsItem]) => new JavacOptionsResult(items.asJava)
      ) {
        case (id, m: JavaModule) =>
          val pathResolver = evaluator.pathsResolver
          T.task {
            val options = m.javacOptions()
            val classpath =
              m.bspCompileClasspath().map(_.resolve(pathResolver)).map(sanitizeUri.apply)
            new JavacOptionsItem(
              id,
              options.asJava,
              classpath.iterator.toSeq.asJava,
              sanitizeUri(m.bspCompileClassesPath().resolve(pathResolver))
            )
          }
      }
    }
}
