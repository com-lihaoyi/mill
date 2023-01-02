package mill.scalalib

import mill.api.{Loose, PathRef, Result, experimental}
import mill.{Agg, BuildInfo, T}
import mill.define.{Ctx, Input, Target, Task}
import mill.scalalib.api.ZincWorkerUtil

import java.nio.file.{CopyOption, Files, LinkOption, StandardCopyOption}
import scala.util.DynamicVariable

@experimental
trait SemanticDbJavaModule extends CoursierModule { hostModule: JavaModule =>

  def semanticDbVersion: Input[String] = T.input {
    T.env.getOrElse(
      "SEMANTICDB_VERSION",
      SemanticDbJavaModule.contextSemanticDbVersion.get()
        .getOrElse(
          SemanticDbJavaModule.buildTimeSemanticDbVersion
        )
    ).asInstanceOf[String]
  }

  def semanticDbJavaVersion: Input[String] = T.input {
    T.env.getOrElse(
      "JAVASEMANTICDB_VERSION",
      SemanticDbJavaModule.contextJavaSemanticDbVersion.get()
        .getOrElse(
          SemanticDbJavaModule.buildTimeJavaSemanticDbVersion
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
    // TODO: add extra options for Java 17+
    def javacOptionsTask(m: JavaModule): Task[Seq[String]] = T.task {
      val more = if(T.log.debugEnabled) " -verbose" else ""
      m.javacOptions() ++ Seq(
        s"-Xplugin:semanticdb -sourceroot:${T.workspace} -targetroot:${T.dest / "classes"} -build-tool:sbt" + more
      )
    }
    def compileClasspathTask(m: JavaModule): Task[Agg[PathRef]] = T.task {
      m.compileClasspath() ++ resolvedSemanticDbJavaPluginIvyDeps()
    }
    def cleanedClassesDir(classesDir: os.Path): PathRef = {
      os.walk(classesDir, preOrder = true)
        .filter(os.isFile)
        .foreach { p =>
          if (p.ext != "semanticdb") {
            os.remove(p)
          }
        }
      PathRef(classesDir)
    }

    hostModule match {
      // TODO: Avoid fetching the Java semanticdb version when there are no Java sources (better detect mixed)
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

          zincWorker.worker()
            .compileMixed(
              upstreamCompileOutput = upstreamCompileOutput(),
              sources = m.allSourceFiles().map(_.path),
              compileClasspath = compileClasspathTask(m)().map(_.path),
              javacOptions = javacOptionsTask(m)(),
              scalaVersion = sv,
              scalaOrganization = m.scalaOrganization(),
              scalacOptions = scalacOptions,
              compilerClasspath = m.scalaCompilerClasspath(),
              scalacPluginClasspath = semanticDbPluginClasspath(),
              reporter = T.reporter.apply(hashCode)
            )
            .map(r => cleanedClassesDir(r.classes.path))
        }
      case m: JavaModule =>
        T.persistent {
          val javacOpts = javacOptionsTask(m)()

          // we currently assume, we don't do incremental java compilation
          os.remove.all(T.dest / "classes")
//          val semDbPath = T.dest / "_semanticdb"
//          os.remove.all(semDbPath)

          T.log.debug(s"effective javac options: ${javacOpts}")

          zincWorker.worker()
            .compileJava(
              upstreamCompileOutput(),
              allSourceFiles().map(_.path),
              compileClasspathTask(m)().map(_.path),
              javacOpts,
              T.reporter.apply(m.hashCode())
            ).map(r => cleanedClassesDir(r.classes.path))
        }
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
