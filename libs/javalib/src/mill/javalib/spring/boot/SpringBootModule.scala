package mill.javalib.spring.boot

import mill.{T, Task}
import mill.api.{ModuleRef, PathRef}
import mill.javalib.{Dep, DepSyntax, MavenModule}

@mill.api.experimental
trait SpringBootModule extends MavenModule { outer =>

  def springBootPlatformVersion: T[String]

  override def bomMvnDeps: T[Seq[Dep]] = Seq(
    mvn"org.springframework.boot:spring-boot-dependencies:${springBootPlatformVersion()}"
  )

  /**
   * The Module holding the Spring Boot tools.
   */
  def springBootToolsModule: ModuleRef[SpringBootToolsModule] = ModuleRef(SpringBootToolsModule)

  /**
   * The group id to be used for SpringBoot's AOT processing. Default is empty string
   */
  def springBootGroupId: T[String] = Task {
    ""
  }

  /**
   * The artifact id to be used for Spring's AOT processing. Default is out
   */
  def springBootArtifactId: T[String] = Task {
    "out"
  }

  def springBootMainClass: T[String] = Task {
    mainClass()
      .toRight("No main class specified")
      .orElse(
        springBootToolsModule()
          .springBootToolsWorker()
          .findSpringBootApplicationClass(localRunClasspath().map(_.path))
      )
      .fold(l => Task.fail(l), r => r)
  }

  /**
   * Spring Boot AOT processing, generating "Fast classes".
   *
   * For more information go to [[https://docs.spring.io/spring-framework/reference/core/aot.html]]
   */
  def springBootProcessAOT: T[Option[PathRef]] = Task {
    val dest = Task.dest
    val classPath = runClasspath().map(_.path)
    val applicationMainClass = springBootMainClass()

    springBootToolsModule().springBootToolsWorker().springBootProcessAOT(
      classPath,
      applicationMainClass,
      dest / "sources",
      dest / "resources",
      dest / "classes",
      springBootGroupId(),
      springBootArtifactId()
    )
    Some(PathRef(dest))
  }

  trait SpringBootOptimisedBuildModule extends SpringBootModule {

    def moduleDeps = Seq(outer)

    def springBootPlatformVersion: T[String] = outer.springBootPlatformVersion()

    override def moduleDir: os.Path = outer.moduleDir

    override def generatedSources: Task.Simple[Seq[PathRef]] = Task {
      val aotGeneratedSources = outer.springBootProcessAOT().map(_.path / "sources").map(PathRef(_))
      outer.generatedSources() ++ aotGeneratedSources
    }

    override def resources: Task.Simple[Seq[PathRef]] = Task {
      val aotGeneratedResources =
        outer.springBootProcessAOT().map(_.path / "resources").map(PathRef(_))
      outer.resources() ++ aotGeneratedResources
    }

    override def compileClasspath: Task.Simple[Seq[PathRef]] = Task {
      val aotClasses = outer.springBootProcessAOT().map(_.path / "classes").map(PathRef(_))
      outer.compileClasspath() ++ aotClasses
    }
  }

  trait SpringBootTestsModule extends SpringBootModule, MavenTests {
    def springBootPlatformVersion: T[String] = outer.springBootPlatformVersion()
  }
}
