package mill
package contrib.docker

import com.google.cloud.tools.jib.api.*
import com.google.cloud.tools.jib.api.buildplan.{ImageFormat, Port}

import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

object JibBuild {

  private val toolName = "mill-contrib-docker-jib"

  def javaBuild(
      sourceImage: JibSourceImage,
      dependencies: Seq[mill.api.PathRef],
      snapshotDependencies: Seq[mill.api.PathRef],
      classes: Seq[mill.api.PathRef],
      jvmOptions: Seq[String],
      mainClass: Option[String],
      mainClassSearchPaths: Seq[mill.api.PathRef],
      logger: mill.api.Logger
  ): JavaContainerBuilder = {

    val javaBuilder = sourceImage match {
      case JibImage.RegistryImage(qualifiedName, credentialsEnvironment) =>
        val image = com.google.cloud.tools.jib.api.RegistryImage.named(
          ImageReference.parse(qualifiedName)
        )
        credentialsEnvironment.foreach { case (username, password) =>
          image.addCredentialRetriever(retrieveEnvCredentials(username, password))
        }
        JavaContainerBuilder.from(image)
      case JibImage.DockerDaemonImage(qualifiedName, _) =>
        JavaContainerBuilder.from(
          DockerDaemonImage.named(ImageReference.parse(qualifiedName))
        )
      case JibImage.SourceTarFile(path) =>
        JavaContainerBuilder.from(TarImage.at(path.path.wrapped))
    }

    javaBuilder.addDependencies(dependencies.map(_.path.wrapped).asJava)
    javaBuilder.addSnapshotDependencies(snapshotDependencies.map(_.path.wrapped).asJava)

    classes
      .filter(p => os.isDir(p.path))
      .map(_.path.wrapped)
      .toSet
      .foreach(javaBuilder.addClasses)
    javaBuilder.addSnapshotDependencies(
      classes.filter(p => os.isFile(p.path)).map(_.path.wrapped).asJava
    )

    javaBuilder.addJvmFlags(jvmOptions.asJava)

    setMainClass(mainClass, mainClassSearchPaths, javaBuilder, logger)

    javaBuilder
  }

  def setContainerParams(
      labels: Map[String, String],
      envVars: Map[String, String],
      exposedPorts: Seq[Int],
      exposedUdpPorts: Seq[Int],
      user: String,
      platforms: Set[JibPlatform],
      imageFormat: JibImageFormat,
      entrypoint: Seq[String],
      programArgs: Seq[String],
      containerBuilder: JibContainerBuilder
  ): JibContainerBuilder = {

    containerBuilder.setEnvironment(envVars.asJava)
    if (platforms.nonEmpty) {
      containerBuilder.setPlatforms(
        platforms
          .map(p => new com.google.cloud.tools.jib.api.buildplan.Platform(p.architecture, p.os))
          .asJava
      )
    }
    containerBuilder.setLabels(labels.asJava)
    containerBuilder.setUser(if (user.isEmpty) null else user)
    containerBuilder.setProgramArguments(programArgs.asJava)
    containerBuilder.setFormat(imageFormat match {
      case JibImageFormat.Docker => ImageFormat.Docker
      case JibImageFormat.OCI => ImageFormat.OCI
    })

    val ports: Set[Port] =
      exposedPorts.map(Port.tcp).toSet ++ exposedUdpPorts.map(Port.udp).toSet
    containerBuilder.setExposedPorts(ports.asJava)
    containerBuilder.setCreationTime(java.time.Instant.now())

    if (entrypoint.nonEmpty) {
      containerBuilder.setEntrypoint(entrypoint.asJava)
    }

    containerBuilder
  }

  def containerize(
      jibBuilder: JibContainerBuilder,
      targetImage: JibTargetImage,
      tags: Seq[String],
      allowInsecureRegistries: Boolean,
      logger: mill.api.Logger,
      dest: os.Path
  ): JibContainer = {
    val containerizer = targetImage match {
      case JibImage.DockerDaemonImage(qualifiedName, _) =>
        Containerizer.to(DockerDaemonImage.named(qualifiedName))
      case JibImage.RegistryImage(qualifiedName, credentialsEnvironment) =>
        val image = com.google.cloud.tools.jib.api.RegistryImage.named(
          ImageReference.parse(qualifiedName)
        )
        credentialsEnvironment.foreach { case (username, password) =>
          image.addCredentialRetriever(retrieveEnvCredentials(username, password))
        }
        Containerizer.to(image)
      case JibImage.TargetTarFile(qualifiedName, filename) =>
        Containerizer.to(TarImage.at((dest / filename).wrapped).named(qualifiedName))
    }

    val containerizerWithLogs = containerizer.addEventHandler(JibLogging.logger(logger))

    tags.foreach(containerizerWithLogs.withAdditionalTag)
    containerizerWithLogs
      .setAllowInsecureRegistries(allowInsecureRegistries)
      .setToolName(toolName)

    jibBuilder.containerize(containerizerWithLogs)
  }

  private def isSnapshotDependency(pathRef: mill.api.PathRef): Boolean =
    pathRef.path.last.endsWith("-SNAPSHOT.jar")

  private def retrieveEnvCredentials(
      usernameEnv: String,
      passwordEnv: String
  ): CredentialRetriever =
    new CredentialRetriever {
      def retrieve(): java.util.Optional[Credential] = {
        val option = for {
          username <- sys.env.get(usernameEnv)
          password <- sys.env.get(passwordEnv)
        } yield Credential.from(username, password)
        option.asJava
      }
    }

  private def setMainClass(
      mainClass: Option[String],
      mainClassSearchPaths: Seq[mill.api.PathRef],
      javaBuilder: JavaContainerBuilder,
      logger: mill.api.Logger
  ): Unit =
    if (mainClass.isDefined) {
      javaBuilder.setMainClass(mainClass.get)
    } else {
      val classfiles = mainClassSearchPaths
        .flatMap(pathRef => os.walk(pathRef.path))
        .collect { case p if os.isFile(p) => p.wrapped }
        .asJava
      val result = MainClassFinder.find(classfiles, JibLogging.eventLogger(logger))
      result.getType match {
        case MainClassFinder.Result.Type.MAIN_CLASS_FOUND =>
          logger.info(s"Main class found: ${result.getFoundMainClass}")
          javaBuilder.setMainClass(result.getFoundMainClass)
        case MainClassFinder.Result.Type.MAIN_CLASS_NOT_FOUND =>
          logger.error("No main class found")
        case MainClassFinder.Result.Type.MULTIPLE_MAIN_CLASSES =>
          logger.info(s"Multiple main classes found, using: ${result.getFoundMainClasses.get(0)}")
          javaBuilder.setMainClass(result.getFoundMainClasses.get(0))
      }
    }

  def partitionDependencies(
      resolvedRunMvnDeps: Seq[mill.api.PathRef]
  ): (Seq[mill.api.PathRef], Seq[mill.api.PathRef]) =
    resolvedRunMvnDeps
      .filter(pathRef => os.exists(pathRef.path))
      .partition(isSnapshotDependency)
}
