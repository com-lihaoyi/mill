package mill.bsp.worker

import ch.epfl.scala.bsp4j.{
  BuildTargetIdentifier,
  JavaBuildServer,
  JavacOptionsItem,
  JavacOptionsParams,
  JavacOptionsResult
}
import mill.T
import mill.bsp.spi.MillBuildServerBase
import mill.scalalib.bsp.BspUri
import mill.scalalib.{JavaModule, SemanticDbJavaModule}

import java.util.concurrent.CompletableFuture
import scala.jdk.CollectionConverters._

class MillJavaBuildServer(base: MillBuildServerBase)
    extends MillBspExtension
    with JavaBuildServer {

  override def extensionCapabilities: ExtensionCapabilities = ExtensionCapabilities(
    languages = Seq("java")
  )

  override def buildTargetJavacOptions(javacOptionsParams: JavacOptionsParams)
      : CompletableFuture[JavacOptionsResult] =
    base.completableTasks(
      s"buildTargetJavacOptions ${javacOptionsParams}",
      targetIds = _ => javacOptionsParams.getTargets.asScala.map(_.bspUri).toSeq,
      tasks = { case m: JavaModule =>
        val classesPathTask = m match {
          case sem: SemanticDbJavaModule if base.enableSemanticDb =>
            sem.bspCompiledClassesAndSemanticDbFiles
          case _ => m.bspCompileClassesPath
        }
        T.task { (classesPathTask(), m.javacOptions(), m.bspCompileClasspath()) }
      }
    ) {
      // We ignore all non-JavaModule
      case (
            ev,
            state,
            id,
            m: JavaModule,
            (classesPath, javacOptions, bspCompileClasspath)
          ) =>
        val pathResolver = ev.pathsResolver
        val options = javacOptions
        val classpath =
          bspCompileClasspath.map(_.resolve(pathResolver)).map(BspUri.sanitizeUri)
        new JavacOptionsItem(
          id.buildTargetIdentifier,
          options.asJava,
          classpath.iterator.toSeq.asJava,
          BspUri.sanitizeUri(classesPath.resolve(pathResolver))
        )
    } {
      new JavacOptionsResult(_)
    }

}
