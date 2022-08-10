package mill.scalalib

import mill.api.{PathRef, Result, experimental}
import mill.{Agg, BuildInfo, T}
import mill.define.{Ctx, Input, Target, Task}
import mill.scalalib.api.ZincWorkerUtil

import java.nio.file.{CopyOption, Files, LinkOption, StandardCopyOption}

@experimental
trait SemanticDbScalaModule extends JavaModule { hostModule =>

  def semanticDbVersion: Input[String] = T.input {
    T.env.getOrElse("SEMANTICDB_VERSION", Versions.semanticDBVersion).asInstanceOf[String]
  }

  def bspClientWantsSemanticDbData: Input[Boolean] = T.input {
    // TODO: transport this info from BSP client somehow
    true
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
           |When using ScalaMetalsSupport with Scala 2 you must provide a semanticDbVersion
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
    val resolvedJars = resolveDeps(semanticDbPluginIvyDeps.map(_.map(_.exclude("*" -> "*"))))()
    resolvedJars.iterator.map(jar => s"-Xplugin:${jar.path}").toSeq
  }

  private def semanticDbPluginClasspath: T[Agg[PathRef]] = hostModule match {
    case m: ScalaModule => T {
        resolveDeps(T { m.scalacPluginIvyDeps() ++ semanticDbPluginIvyDeps() })()
      }
    case _ => T {
        resolveDeps(semanticDbPluginIvyDeps)()
      }
  }

  def semanticDbData: T[PathRef] = hostModule match {
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
            compilerClasspath = m.scalaCompilerClasspath().map(_.path),
            scalacPluginClasspath = semanticDbPluginClasspath().map(_.path),
            reporter = T.reporter.apply(hashCode)
          )
          .map(_.classes)
      }
  }

  def compileClassAndSemanticDbFiles = T {
    val dest = T.dest
    val classes = compile().classes.path
    val sems = semanticDbData().path
    if (os.exists(sems)) os.copy(sems, dest, mergeFolders = true)
    if (os.exists(classes)) os.copy(classes, dest, mergeFolders = true, replaceExisting = true)
    PathRef(dest)
  }

  def bspCompileClassAndSemanticDbFiles: Target[UnresolvedPath] = {
    if (
      compile.ctx.enclosing == s"${classOf[SemanticDbScalaModule].getName}#compileClassAndSemanticDbFiles"
    ) {
      T {
        T.log.debug(
          s"compileClassAndSemanticDbFiles target was not overridden, assuming hard-coded classes directory for target ${compileClassAndSemanticDbFiles}"
        )
        UnresolvedPath.DestPath(
          os.sub,
          compileClassAndSemanticDbFiles.ctx.segments,
          compileClassAndSemanticDbFiles.ctx.foreign
        )
      }
    } else {
      T {
        T.log.debug(
          s"compileClassAndSemanticDbFiles target was overridden, need to actually execute compilation to get the compiled classes directory for target ${compileClassAndSemanticDbFiles}"
        )
        UnresolvedPath.ResolvedPath(compileClassAndSemanticDbFiles().path)
      }
    }
  }

}
