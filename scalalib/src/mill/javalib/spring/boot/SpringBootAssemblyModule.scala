package mill.javalib.spring.boot

import mill.api.{PathRef, Result}
import mill.{Agg, T, Task}
import mill.define.ModuleRef
import mill.javalib.JavaModule
import mill.scalalib.{AssemblyModule, RunModule}
import mill.util.Jvm

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
        mainClass = "org.springframework.boot.loader.JarLauncher",
        shellClassPath = Agg("$0"),
        cmdClassPath = Agg("%~dpnx0"),
        shebang = false,
        jvmArgs = forkArgs(),
        shellJvmArgs = forkShellArgs(),
        cmdJvmArgs = forkCmdArgs()
      )
    ).filter(_.nonEmpty)
  }

  /**
   * The Class holding the Spring Boot Application entrypoint.
   * This used the [[RUnMdoule.mainClass]] (if defined) or falls back to a auto-detection of a `SpringBootApplication` class.
   */
  def springBootMainClass: T[String] = {
    def handleResult(res: Either[String, String]): Result[String] = res match {
      case Right(r) => Result.Success(r)
      case Left(l) => Result.Failure(l)
    }

    this match {
      //      case m: JavaModule => Task {
      //          handleResult(
      //            m.mainClass()
      //              .toRight("No main class specified")
      //              .orElse(
      //                springBootToolsModule().springBootToolsWorker()
      //                  .findMainClass(m.compile().classes.path)
      //              )
      //          )
      //        }

      case m: RunModule => Task {
          handleResult(
            m.mainClass()
              .toRight("No main class specified")
              .orElse(
                springBootToolsModule()
                  .springBootToolsWorker()
                  .findMainClass(m.localRunClasspath().map(_.path))
              )
          )
        }
      case m => Task {
          handleResult(
            springBootToolsModule()
              .springBootToolsWorker()
              .findMainClass(m.localClasspath().map(_.path))
          )
        }
    }
  }

  def springBootAssembly: T[PathRef] = Task {

    val libs = runClasspath().map(_.path)
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
        libs = libs,
        assemblyScript = script
      )

    PathRef(dest)
  }

}
