package mill.scalalib

import mill.api.{PathRef, Result, experimental}
import mill.define.{Input, Target, Task}
import mill.scalalib.api.ZincWorkerUtil
import mill.util.Version
import mill.{Agg, BuildInfo, T}

import scala.util.Properties

@experimental
trait SemanticDbJavaModule extends CoursierModule { hostModule: JavaModule =>

  def semanticDbVersion: Input[String] = T.input {
    val builtin = SemanticDbJavaModule.buildTimeSemanticDbVersion
    val requested = T.env.getOrElse[String](
      "SEMANTICDB_VERSION",
      SemanticDbJavaModule.contextSemanticDbVersion.get().getOrElse(builtin)
    )
    Version.chooseNewest(requested, builtin)(Version.IgnoreQualifierOrdering)
  }

  def semanticDbJavaVersion: Input[String] = T.input {
    val builtin = SemanticDbJavaModule.buildTimeJavaSemanticDbVersion
    val requested = T.env.getOrElse[String](
      "JAVASEMANTICDB_VERSION",
      SemanticDbJavaModule.contextJavaSemanticDbVersion.get().getOrElse(builtin)
    )
    Version.chooseNewest(requested, builtin)(Version.IgnoreQualifierOrdering)
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

  private def semanticDbJavaPluginIvyDeps: Target[Agg[Dep]] = T {
    val sv = semanticDbJavaVersion()
    if (sv.isEmpty) {
      val msg =
        """|
           |You must provide a javaSemanticDbVersion
           |
           |def semanticDbJavaVersion = ???
           |""".stripMargin
      Result.Failure(msg)
    } else {
      Result.Success(Agg(
        ivy"com.sourcegraph:semanticdb-javac:${sv}"
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

  private def resolvedSemanticDbJavaPluginIvyDeps: Target[Agg[PathRef]] = T {
    resolveDeps(T.task { semanticDbJavaPluginIvyDeps().map(bindDependency()) })()
  }

  def semanticDbData: T[PathRef] = {
    def javacOptionsTask(m: JavaModule): Task[Seq[String]] = T.task {
      // these are only needed for Java 17+
      val extracJavacExports =
        if (Properties.isJavaAtLeast(17)) List(
          "-J--add-exports",
          "-Jjdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
          "-J--add-exports",
          "-Jjdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED",
          "-J--add-exports",
          "-Jjdk.compiler/com.sun.tools.javac.model=ALL-UNNAMED",
          "-J--add-exports",
          "-Jjdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED",
          "-J--add-exports",
          "-Jjdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED"
        )
        else List.empty

      val isNewEnough =
        Version.isAtLeast(semanticDbJavaVersion(), "0.8.10")(Version.IgnoreQualifierOrdering)
      val buildTool = s" -build-tool:${if (isNewEnough) "mill" else "sbt"}"
      val verbose = if (T.log.debugEnabled) " -verbose" else ""
      m.javacOptions() ++ Seq(
        s"-Xplugin:semanticdb -sourceroot:${T.workspace} -targetroot:${T.dest / "classes"}${buildTool}${verbose}"
      ) ++ extracJavacExports

    }
    def compileClasspathTask(m: JavaModule): Task[Agg[PathRef]] = T.task {
      m.compileClasspath() ++ resolvedSemanticDbJavaPluginIvyDeps()
    }

    // The semanticdb-javac plugin has issues with the -sourceroot setting, so we correct this on the fly
    def copySemanticdbFiles(
        classesDir: os.Path,
        sourceroot: os.Path,
        targetDir: os.Path
    ): PathRef = {
      os.remove.all(targetDir)
      os.makeDir.all(targetDir)

      val ups = sourceroot.segments.size
      val semanticPath = os.rel / "META-INF" / "semanticdb"
      val toClean = classesDir / semanticPath / sourceroot.segments.toSeq

      os.walk(classesDir, preOrder = true)
        .filter(os.isFile)
        .foreach { p =>
          if (p.ext == "semanticdb") {
            val target =
              if (ups > 0 && p.startsWith(toClean)) {
                targetDir / semanticPath / p.relativeTo(toClean)
              } else {
                targetDir / p.relativeTo(classesDir)
              }
            os.move(p, target, createFolders = true)
          }
        }
      PathRef(targetDir)
    }

    hostModule match {
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

          val javacOpts = javacOptionsTask(m)()

          T.log.debug(s"effective scalac options: ${scalacOptions}")
          T.log.debug(s"effective javac options: ${javacOpts}")

          zincWorker.worker()
            .compileMixed(
              upstreamCompileOutput = upstreamCompileOutput(),
              sources = m.allSourceFiles().map(_.path),
              compileClasspath = compileClasspathTask(m)().map(_.path),
              javacOptions = javacOpts,
              scalaVersion = sv,
              scalaOrganization = m.scalaOrganization(),
              scalacOptions = scalacOptions,
              compilerClasspath = m.scalaCompilerClasspath(),
              scalacPluginClasspath = semanticDbPluginClasspath(),
              reporter = T.reporter.apply(hashCode),
              reportCachedProblems = zincReportCachedProblems()
            )
            .map(r => copySemanticdbFiles(r.classes.path, T.workspace, T.dest / "data"))
        }
      case m: JavaModule =>
        T.persistent {
          val javacOpts = javacOptionsTask(m)()

          // we currently assume, we don't do incremental java compilation
          os.remove.all(T.dest / "classes")

          T.log.debug(s"effective javac options: ${javacOpts}")

          zincWorker.worker()
            .compileJava(
              upstreamCompileOutput = upstreamCompileOutput(),
              sources = allSourceFiles().map(_.path),
              compileClasspath = compileClasspathTask(m)().map(_.path),
              javacOptions = javacOpts,
              reporter = T.reporter.apply(m.hashCode()),
              reportCachedProblems = zincReportCachedProblems()
            ).map(r => copySemanticdbFiles(r.classes.path, T.workspace, T.dest / "data"))
        }
    }
  }

  // keep in sync with bspCompiledClassesAndSemanticDbFiles
  def compiledClassesAndSemanticDbFiles: Target[PathRef] = T {
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
      compiledClassesAndSemanticDbFiles.ctx.enclosing == s"${classOf[SemanticDbJavaModule].getName}#compiledClassesAndSemanticDbFiles"
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
  val buildTimeJavaSemanticDbVersion = Versions.semanticDbJavaVersion
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
