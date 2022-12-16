package mill.scalalib

import mill.api.{PathRef, Result, experimental}
import mill.{Agg, BuildInfo, T}
import mill.define.{Ctx, Input, Target, Task}
import mill.scalalib.api.ZincWorkerUtil

import java.nio.file.{CopyOption, Files, LinkOption, StandardCopyOption}
import scala.util.DynamicVariable

@experimental
trait SemanticDbJavaModule extends JavaModule { hostModule =>

  def semanticDbVersion: Input[String] = T.input {
    T.env.getOrElse(
      "SEMANTICDB_VERSION",
      SemanticDbJavaModule.contextSemanticDbVersion.get()
        .getOrElse(
          SemanticDbJavaModule.buildTimeSemanticDbVersion
        )
    ).asInstanceOf[String]
  }

  def semanticDbScalaVersion = hostModule match {
    case m: ScalaModule => T { m.scalaVersion }
    case _ => T { BuildInfo.scalaVersion }
  }

  private def semanticDbPluginIvyDeps: Target[Agg[Dep]] = T {
    val sv = semanticDbScalaVersion()
    val semDbVersion = semanticDbVersion()
    if (!ZincWorkerUtil.isScala3(sv) && semDbVersion.isEmpty) {
      val msg =
        """|
           |With Scala 2 you must provide a semanticDbVersion
           |
           |def semanticDbVersion = ???
           |""".stripMargin
      Result.Failure(msg)
    } else if (ZincWorkerUtil.isScala3(sv)) {
      Result.Success(Agg.empty[Dep])
    } else {
      Result.Success(Agg(
        ivy"org.scalameta:semanticdb-scalac_${sv}:${semDbVersion}"
      ))
    }
  }

  /**
   * Scalac options to activate the compiler plugins.
   */
  private def semanticDbEnablePluginScalacOptions: Target[Seq[String]] = T {
    val resolvedJars = resolveDeps(T.task {
      val bind = bindDependency()
      semanticDbPluginIvyDeps().map(_.exclude("*" -> "*")).map(bind)
    })()
    resolvedJars.iterator.map(jar => s"-Xplugin:${jar.path}").toSeq
  }

  private def semanticDbPluginClasspath: T[Agg[PathRef]] = hostModule match {
    case m: ScalaModule => T {
        resolveDeps(T.task {
          val bind = bindDependency()
          (m.scalacPluginIvyDeps() ++ semanticDbPluginIvyDeps()).map(bind)
        })()
      }
    case _ => T {
        resolveDeps(T.task {
          val bind = bindDependency()
          semanticDbPluginIvyDeps().map(bind)
        })()
      }
  }

  def semanticDbData: T[PathRef] = hostModule match {
    // TODO: also generate semanticDB for Java and Mixed-Java projects
    case m: ScalaModule =>
      T.persistent {
        val sv = m.scalaVersion()

        val scalacOptions = (
          m.allScalacOptions() ++
            semanticDbEnablePluginScalacOptions() ++ {
              if (ZincWorkerUtil.isScala3(sv)) {
                Seq("-Xsemanticdb")
              } else {
                Seq(
                  "-Yrangepos",
                  s"-P:semanticdb:sourceroot:${T.workspace}",
                  "-Ystop-after:semanticdb-typer"
                )
              }
            }
        )
          .filterNot(_ == "-Xfatal-warnings")
        T.log.debug(s"effective scalac options: ${scalacOptions}")

        zincWorker
          .worker()
          .compileMixed(
            upstreamCompileOutput = upstreamCompileOutput(),
            sources = m.allSourceFiles().map(_.path),
            compileClasspath = compileClasspath().map(_.path),
            javacOptions = javacOptions(),
            scalaVersion = sv,
            scalaOrganization = m.scalaOrganization(),
            scalacOptions = scalacOptions,
            compilerClasspath = m.scalaCompilerClasspath(),
            scalacPluginClasspath = semanticDbPluginClasspath(),
            reporter = T.reporter.apply(hashCode)
          )
          .map(_.classes)
      }
  }

  // keep in sync with bspCompiledClassesAndSemanticDbFiles
  def compiledClassesAndSemanticDbFiles = T {
    val dest = T.dest
    val classes = compile().classes.path
    val sems = semanticDbData().path
    if (os.exists(sems)) os.copy(sems, dest, mergeFolders = true)
    if (os.exists(classes)) os.copy(classes, dest, mergeFolders = true, replaceExisting = true)
    PathRef(dest)
  }

  // keep in sync with compiledClassesAndSemanticDbFiles
  def bspCompiledClassesAndSemanticDbFiles: Target[UnresolvedPath] = {
    if (
      compile.ctx.enclosing == s"${classOf[SemanticDbJavaModule].getName}#compiledClassesAndSemanticDbFiles"
    ) {
      T {
        T.log.debug(
          s"compiledClassesAndSemanticDbFiles target was not overridden, assuming hard-coded classes directory for target ${compiledClassesAndSemanticDbFiles}"
        )
        UnresolvedPath.DestPath(
          os.sub,
          compiledClassesAndSemanticDbFiles.ctx.segments,
          compiledClassesAndSemanticDbFiles.ctx.foreign
        )
      }
    } else {
      T {
        T.log.debug(
          s"compiledClassesAndSemanticDbFiles target was overridden, need to actually execute compilation to get the compiled classes directory for target ${compiledClassesAndSemanticDbFiles}"
        )
        UnresolvedPath.ResolvedPath(compiledClassesAndSemanticDbFiles().path)
      }
    }
  }

}

object SemanticDbJavaModule {
  val buildTimeSemanticDbVersion = Versions.semanticDBVersion

  private[mill] val contextSemanticDbVersion: InheritableThreadLocal[Option[String]] =
    new InheritableThreadLocal[Option[String]] {
      protected override def initialValue(): Option[String] = None.asInstanceOf[Option[String]]
    }

  private[mill] val contextJavaSemanticDbVersion: InheritableThreadLocal[Option[String]] =
    new InheritableThreadLocal[Option[String]] {
      protected override def initialValue(): Option[String] = None.asInstanceOf[Option[String]]
    }

  private[mill] def resetContext(): Unit = {
    contextJavaSemanticDbVersion.set(None)
    contextSemanticDbVersion.set(None)
  }
}
