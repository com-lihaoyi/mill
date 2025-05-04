package mill.javalib.spring.boot

import mill.{T, Task}
import mill.api.Result
import mill.define.ModuleRef
import mill.define.PathRef
import mill.javalib.JavaModule
import mill.scalalib.RunModule
import mill.util.Jvm
import mill.scalalib.PublishModule

trait SpringBootAssemblyModule extends JavaModule {

  /**
   * The Module holding the Spring Boot tools.
   */
  def springBootToolsModule: ModuleRef[SpringBootToolsModule] = ModuleRef(SpringBootToolsModule)

  /**
   * A script prepended to the resulting `springBootAssembly` to make it executable.
   * This uses the same prepend script as Mill `JavaModule` does,
   * so it supports most Linux/Unix shells (probably not `fish`)
   * as well as Windows cmd shell (the file needs a `.bat` or `.cmd` extension).
   * Set it to `""` if you don't want an executable JAR.
   */
  def springBootPrependScript: T[Option[String]] = Task {
    Option(
      Jvm.launcherUniversalScript(
        mainClass = "org.springframework.boot.loader.launch.JarLauncher",
        shellClassPath = Seq("$0"),
        cmdClassPath = Seq("%~dpnx0"),
        shebang = false,
        jvmArgs = forkArgs(),
        shellJvmArgs = forkShellArgs(),
        cmdJvmArgs = forkCmdArgs()
      )
    ).filter(_.nonEmpty)
  }

  /**
   * The Class holding the Spring Boot Application entrypoint.
   * This uses the [[RunMdoule.mainClass]] (if defined) or falls back to a auto-detection of a `SpringBootApplication` class.
   */
  def springBootMainClass: T[String] = Task {
    mainClass()
      .toRight("No main class specified")
      .orElse(
        springBootToolsModule()
          .springBootToolsWorker()
          .findMainClass(localRunClasspath().map(_.path))
      )
      .fold(l => Task.fail(l), r => r)

  }

  def springBootAssemblyUpstreamIvyAssemblyClasspath: T[Seq[PathRef]] = Task {
    resolvedRunMvnDeps()
  }
  def springBootAssemblyUpstreamLocalAssemblyClasspath: T[Seq[PathRef]] = Task {
    Task.traverse(transitiveModuleRunModuleDeps)(m =>
      // We need to renamed potential duplicated jar names (`out.jar`)
      // Instead of doing it in-place, we do it in the context of each module,
      // to avoid re-doing it for all jars when something changes
      m.springBootAssemblyModule.springBootEmbeddableJar
    )()
  }

  def springBootAssembly: T[PathRef] = Task {

    val libs = springBootAssemblyUpstreamIvyAssemblyClasspath() ++
      springBootAssemblyUpstreamLocalAssemblyClasspath()
    val base = jar().path
    val mainClass = springBootMainClass()
    val dest = T.dest / "out.jar"
    val script = springBootPrependScript()

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

  private implicit class EmbeddableSpringBootModule(jm: JavaModule) extends mill.define.Module {
    override def moduleCtx = jm.moduleCtx
    // Hacky way to attach some generated resources under the context of the task dependency
    object springBootAssemblyModule extends mill.define.Module {
      def springBootEmbeddableJar = jm match {
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
