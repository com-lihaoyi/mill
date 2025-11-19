package mill.javalib.spring.boot

import mill.{T, Task}
import mill.api.{ModuleRef, PathRef}
import mill.javalib.{Dep, DepSyntax, MavenModule, NativeImageModule, RunModule}

import java.util.Properties
import scala.util.Using

@mill.api.experimental
trait SpringBootModule extends MavenModule {
  outer =>

  /** Spring boot version as can be found in [[https://start.spring.io/]] */
  def springBootPlatformVersion: T[String]

  /** org.springframework.boot:spring-boot-dependencies with [[springBootPlatformVersion]] as the version */
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
   * The artifact id to be used for Spring's AOT processing. Default is [[artifactName]]
   */
  def springBootArtifactId: T[String] = Task {
    artifactName()
  }

  /**
   * Uses the [[springBootToolsModule]] to find the SpringBootApplicationClass from the [[localRunClasspath]]
   */
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
   * Extra args passed to "org.springframework.boot.SpringApplicationAotProcessor"
   *
   * For more information go to [[https://docs.spring.io/spring-framework/reference/core/aot.html]]
   */
  def springBootProcessAOTExtraApplicationArgs: T[Seq[String]] = Task {
    Seq.empty[String]
  }

  /**
   * Spring Boot AOT processing, generating "Fast classes".
   *
   * For more information go to [[https://docs.spring.io/spring-framework/reference/core/aot.html]]
   */
  def springBootProcessAOT: T[PathRef] = Task {
    val dest = Task.dest
    runClasspath().map(_.path)
    val applicationMainClass = springBootMainClass()

    val sourceOut = dest / "sources"
    val resourceOut = dest / "resources"
    val classOut = dest / "classes"
    val groupId = springBootGroupId()
    val artifactId = springBootArtifactId()

    val args: Array[String] = Array(
      applicationMainClass,
      sourceOut.toString,
      resourceOut.toString,
      classOut.toString,
      groupId,
      artifactId
    ) ++ springBootProcessAOTExtraApplicationArgs()

    mill.util.Jvm.withClassLoader(
      runClasspath().map(_.path)
    ) {
      classloader =>
        RunModule.getMainMethod(
          "org.springframework.boot.SpringApplicationAotProcessor",
          classloader
        )
          .invoke(null, args)
    }

    PathRef(dest)
  }

  trait SpringBootOptimisedBuildModule extends SpringBootModule {

    def moduleDeps = Seq(outer)

    def springBootPlatformVersion: T[String] = outer.springBootPlatformVersion()

    override def moduleDir: os.Path = outer.moduleDir

    override def generatedSources: Task.Simple[Seq[PathRef]] = Task {
      val aotGeneratedSources = Seq(PathRef(outer.springBootProcessAOT().path / "sources"))
      outer.generatedSources() ++ aotGeneratedSources
    }

    override def resources: Task.Simple[Seq[PathRef]] = Task {
      val aotGeneratedResources =
        Seq(PathRef(outer.springBootProcessAOT().path / "resources"))
      outer.resources() ++ aotGeneratedResources
    }

    override def compileClasspath: Task.Simple[Seq[PathRef]] = Task {
      val aotClasses = Seq(PathRef(outer.springBootProcessAOT().path / "classes"))
      outer.compileClasspath() ++ aotClasses
    }
  }

  trait NativeSpringBootBuildModule extends SpringBootOptimisedBuildModule, NativeImageModule {
    override def nativeImageOptions: Task.Simple[Seq[String]] = Task {
      val aotDir: os.Path = outer.springBootProcessAOT().path
      val groupId = if (springBootGroupId().isEmpty)
        "unspecified"
      else
        springBootGroupId()

      val nativeImageArgs: Seq[String] =
        val nativeImageProps =
          aotDir / "resources/META-INF/native-image" / groupId / artifactId() / "native-image.properties"
        if (os.exists(nativeImageProps) && os.isFile(nativeImageProps)) {
          val properties = new Properties()
          Using.resource[
            java.io.InputStream,
            Unit
          ](os.read.inputStream(nativeImageProps))(properties.load)
          properties.getProperty("Args").split(" ")
        } else
          Seq.empty

      nativeImageArgs
    }
  }

  trait SpringBootTestsModule extends SpringBootModule, MavenTests {
    def springBootPlatformVersion: T[String] = outer.springBootPlatformVersion()
  }
}
