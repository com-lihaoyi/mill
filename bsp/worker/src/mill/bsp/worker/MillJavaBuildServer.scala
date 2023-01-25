package mill.bsp.worker

import ch.epfl.scala.bsp4j.{
  JavaBuildServer,
  JavacOptionsItem,
  JavacOptionsParams,
  JavacOptionsResult
}
import mill.T
import mill.api.internal
import mill.scalalib.{JavaModule, SemanticDbJavaModule}

import java.util.concurrent.CompletableFuture
import scala.jdk.CollectionConverters._

@internal
trait MillJavaBuildServer extends JavaBuildServer { this: MillBuildServer =>
  override def buildTargetJavacOptions(javacOptionsParams: JavacOptionsParams)
      : CompletableFuture[JavacOptionsResult] =
    completable(s"buildTargetJavacOptions ${javacOptionsParams}") { state =>
      targetTasks(
        state,
        targetIds = javacOptionsParams.getTargets.asScala.toSeq,
        agg = (items: Seq[JavacOptionsItem]) => new JavacOptionsResult(items.asJava)
      ) {
        case (id, m: JavaModule) =>
          val classesPathTask = m match {
            case sem: SemanticDbJavaModule if clientWantsSemanticDb =>
              sem.bspCompiledClassesAndSemanticDbFiles
            case _ => m.bspCompileClassesPath
          }

          val pathResolver = state.evaluator.pathsResolver
          T.task {
            val options = m.javacOptions()
            val classpath =
              m.bspCompileClasspath().map(_.resolve(pathResolver)).map(sanitizeUri.apply)
            new JavacOptionsItem(
              id,
              options.asJava,
              classpath.iterator.toSeq.asJava,
              sanitizeUri(classesPathTask().resolve(pathResolver))
            )
          }
      }
    }
}
