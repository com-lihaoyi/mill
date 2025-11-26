package mill.javalib.repackage

import mill.{T, Task}
import mill.api.Result
import mill.api.ModuleRef
import mill.api.PathRef
import mill.javalib.JavaModule
import mill.util.Jvm
import mill.javalib.PublishModule
import mill.javalib.RunModule
import mill.javalib.AssemblyModule
import mill.javalib.spring.boot.SpringBootToolsModule

/**
 * Support to produce self-executable assemblies with the `Repackager` from the Spring Boot Tools suite
 * (https://docs.spring.io/spring-boot/build-tool-plugin/index.html).
 *
 * This is an alternative to the [[mill.scalalib.AssemblyModule]].
 */
trait RepackageModule extends mill.api.Module {

  /**
   * The Module holding the Spring Boot tools.
   */
  def springBootToolsModule: ModuleRef[SpringBootToolsModule] = ModuleRef(SpringBootToolsModule)

  /**
   * A script prepended to the resulting [[repackagedJar]] to make it executable.
   * This uses the same prepend script as Mill [[JavaModule]] does,
   * so it supports most Linux/Unix shells (probably not `fish`)
   * as well as Windows cmd shell (the file needs a `.bat` or `.cmd` extension).
   * Set it to [[None]] if you don't want an executable JAR.
   */
  def repackagePrependScript: T[Option[String]] = this match {
    case m: AssemblyModule => Task {
        val mainClass =
          if (
            mill.util.Version
              .isAtLeast(springBootToolsModule().springBootToolsVersion(), "3.2.0-RC1")(using
                mill.util.Version.MavenOrdering
              )
          ) {
            // See issue: https://github.com/spring-projects/spring-boot/issues/37667
            "org.springframework.boot.loader.launch.JarLauncher"
          } else {
            "org.springframework.boot.loader.JarLauncher"
          }

        Option(
          Jvm.launcherUniversalScript(
            mainClass = mainClass,
            shellClassPath = Seq("$0"),
            cmdClassPath = Seq("%~dpnx0"),
            shebang = false,
            jvmArgs = m.forkArgs().toStringSeq,
            shellJvmArgs = m.forkShellArgs().toStringSeq,
            cmdJvmArgs = m.forkCmdArgs().toStringSeq
          )
        ).filter(_.nonEmpty)
      }
    case _ => Task {
        Task.fail("You need to override repackagePrependScript or inherit `JavaModule`")
      }
  }

  /**
   * The Class holding the Application entrypoint.
   * This uses the [[RunModule.mainClass]] (if defined) or falls back to auto-detection of a `SpringBootApplication` class.
   */
  def repackageMainClass: T[String] = this match {
    case m: RunModule => Task {
        m.mainClass()
          .toRight("No main class specified")
          .orElse(
            springBootToolsModule()
              .springBootToolsWorker()
              .findSpringBootApplicationClass(m.localRunClasspath().map(_.path))
          )
          .fold(l => Task.fail(l), r => r)
      }
    case _ => Task {
        Task.fail("You need to override repackageMainClass or inherit `RunModule`")
      }
  }

  /** The upstream Maven dependencies as JARs, to be embedded in the [[repackagedJar]]. */
  def repackageUpstreamMvnJars: T[Seq[PathRef]] = this match {
    case m: JavaModule => Task {
        m.resolvedRunMvnDeps()
      }
    case _ => Task {
        Task.fail("You need to override repackageUpstreamMvnJars or inherit `JavaModule`")
      }
  }

  /** The upstream Module dependencies as JARs, to be embedded in the [[repackagedJar]]. */
  def repackageUpstreamLocalJars: T[Seq[PathRef]] = this match {
    case m: JavaModule => Task {
        Task.traverse(m.transitiveModuleRunModuleDeps)(m =>
          // We need to renamed potential duplicated jar names (`out.jar`)
          // Instead of doing it in-place, we do it in the context of each module,
          // to avoid re-doing it for all jars when something changes
          m.springBootAssemblyModule.artifactJar
        )()
      }
    case _ => Task {
        Task.fail("You need to override repackageUpstreamLocalJars or inherit `JavaModule`")
      }
  }

  def repackageBaseJar: T[PathRef] = this match {
    case m: JavaModule => Task { m.jar() }
    case _ => Task {
        Task.fail("You need to override repackageBaseJar or inherit `JavaModule`")
      }
  }

  def repackagedJar: T[PathRef] = Task {

    val libs = repackageUpstreamMvnJars() ++
      repackageUpstreamLocalJars()
    val base = repackageBaseJar().path
    val mainClass = repackageMainClass()
    val dest = Task.dest / "out.jar"
    val script = repackagePrependScript()

    springBootToolsModule()
      .springBootToolsWorker()
      .repackageJar(
        dest = dest,
        base = base,
        mainClass = mainClass,
        libs = libs.map(_.path),
        assemblyScript = script
      )

    PathRef(dest)
  }

  // Hack-ish way to attach some generated resources under the context of the task dependency
  private implicit class EmbeddableSpringBootModule(jm: JavaModule) extends mill.api.Module {
    override def moduleCtx = jm.moduleCtx
    object springBootAssemblyModule extends mill.api.Module {

      /** Same as [[jar]] but with a unique name to avoid name collisions. */
      def artifactJar: T[PathRef] = jm match {
        case m: PublishModule => Task {
            val dest = Task.dest / s"${m.artifactId()}-${m.publishVersion()}.jar"
            os.copy(m.jar().path, dest)
            PathRef(dest)
          }
        case m => Task {
            val dest = Task.dest / s"${m.artifactId()}.jar"
            os.copy(m.jar().path, dest)
            PathRef(dest)
          }
      }
    }
  }
}
