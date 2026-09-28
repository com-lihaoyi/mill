package mill.contrib.vaadin.worker

import com.vaadin.experimental.FeatureFlags
import com.vaadin.flow.internal.FrontendUtils
import com.vaadin.flow.plugin.base.{BuildFrontendUtil, PluginAdapterBuild}
import com.vaadin.flow.server.Constants
import com.vaadin.flow.server.frontend.{
  BundleValidationUtil,
  FrontendTools,
  Options,
  TaskCleanFrontendFiles
}
import com.vaadin.flow.server.frontend.scanner.{ClassFinder, FrontendDependenciesScanner}
import com.vaadin.pro.licensechecker.{LicenseChecker, MissingLicenseKeyException}
import mill.contrib.vaadin.api.VaadinWorkerApi

import java.io.{File, PrintWriter, StringWriter}
import java.net.URI
import java.nio.file.Path
import java.util.function.Consumer
import scala.jdk.CollectionConverters.*

/**
 * Implements Vaadin's [[PluginAdapterBuild]] SPI to drive the production
 * frontend build ("prepare-frontend" + "build-frontend"), mirroring what the
 * `vaadin-maven-plugin` and `vaadin-gradle-plugin` do.
 *
 * The stage dir follows the servlet resource layout: the adapter's servlet
 * resource output directory is the `META-INF/VAADIN` folder under it (token
 * file: `META-INF/VAADIN/config/flow-build-info.json`, bundle:
 * `META-INF/VAADIN/webapp`).
 */
class VaadinBuildAdapter(config: VaadinWorkerApi.Config, log: VaadinWorkerApi.Logger)
    extends PluginAdapterBuild {

  private val servletResourceOutDir =
    config.stageDir().resolve(Constants.VAADIN_SERVLET_RESOURCES).toFile

  servletResourceOutDir.mkdirs()

  private val classpath: Seq[Path] = config.classpath().toSeq

  /** Created on first use; closed at the end of [[run]] to release its jar handles. */
  private var classFinder: Option[ClassFinder] = None

  def run(): Unit =
    try runBuild()
    finally classFinder.foreach {
        case closeable: AutoCloseable => closeable.close()
        case _ => ()
      }

  private def runBuild(): Unit = {
    // Mirrors the Maven lifecycle order: prepare-frontend goal, then
    // build-frontend goal (as orchestrated by BuildFrontendMojo).
    BuildFrontendUtil.propagateBuildInfo(this)
    BuildFrontendUtil.prepareFrontend(this)

    val featureFlags = new FeatureFlags(createLookup(getClassFinder()))
    featureFlags.setPropertiesLocation(javaResourceFolder())

    val reactEnabled = isReactEnabled() && FrontendUtils.isReactRouterRequired(
      BuildFrontendUtil.getFrontendDirectory(this)
    )
    val frontendDependencies =
      new FrontendDependenciesScanner.FrontendDependenciesScannerFactory()
        .createScanner(
          !optimizeBundle(),
          getClassFinder(),
          generateEmbeddableWebComponents(),
          featureFlags,
          reactEnabled
        )

    BuildFrontendUtil.runNodeUpdater(this, frontendDependencies)

    if (generateBundle() && BundleValidationUtil.needsBundleBuild(servletResourceOutDir)) {
      BuildFrontendUtil.runFrontendBuild(this)
      val options = new Options(null, getClassFinder(), npmFolder())
        .withFrontendDirectory(BuildFrontendUtil.getFrontendDirectory(this))
        .withFrontendGeneratedFolder(generatedTsFolder())
      new TaskCleanFrontendFiles(options).execute()
    }

    // Static state, but confined to the worker's classloader.
    LicenseChecker.setStrictOffline(true)
    var licenseRequired = false
    var commercialBannerRequired = false
    try {
      licenseRequired = BuildFrontendUtil.validateLicenses(this, frontendDependencies)
    } catch {
      case e: MissingLicenseKeyException =>
        logInfo(e.getMessage)
        licenseRequired = true
        commercialBannerRequired = true
    }
    BuildFrontendUtil.updateBuildFile(this, licenseRequired, commercialBannerRequired)
  }

  override def applicationProperties(): File =
    config.resourcesDir().resolve("application.properties").toFile

  override def eagerServerLoad(): Boolean = false

  override def frontendDirectory(): File = config.frontendDir().toFile

  override def generatedTsFolder(): File = config.frontendDir().resolve("generated").toFile

  override def getClassFinder(): ClassFinder = classFinder.getOrElse {
    val finder = BuildFrontendUtil.getClassFinder(classpath.map(_.toString).asJava)
    classFinder = Some(finder)
    finder
  }

  override def getJarFiles(): java.util.Set[File] =
    classpath.filter(_.toString.endsWith(".jar")).map(_.toFile).toSet.asJava

  override def isJarProject(): Boolean = false

  override def isDebugEnabled(): Boolean = false

  override def javaSourceFolder(): File = config.javaSourceDir().toFile

  override def javaResourceFolder(): File = config.resourcesDir().toFile

  private def withStackTrace(message: CharSequence, throwable: Throwable): String = {
    val trace = new StringWriter()
    throwable.printStackTrace(new PrintWriter(trace))
    s"$message${System.lineSeparator()}$trace"
  }

  override def logDebug(debugMessage: CharSequence): Unit = log.debug(debugMessage.toString)

  override def logDebug(debugMessage: CharSequence, throwable: Throwable): Unit =
    log.debug(withStackTrace(debugMessage, throwable))

  override def logInfo(infoMessage: CharSequence): Unit = log.info(infoMessage.toString)

  override def logWarn(warningMessage: CharSequence): Unit = log.warn(warningMessage.toString)

  override def logError(errorMessage: CharSequence): Unit = log.error(errorMessage.toString)

  override def logWarn(warningMessage: CharSequence, throwable: Throwable): Unit =
    log.warn(withStackTrace(warningMessage, throwable))

  override def logError(errorMessage: CharSequence, throwable: Throwable): Unit =
    log.error(withStackTrace(errorMessage, throwable))

  override def nodeDownloadRoot(): URI = URI.create("https://nodejs.org/dist/")

  override def nodeVersion(): String = FrontendTools.DEFAULT_NODE_VERSION

  override def npmFolder(): File = config.projectDir().toFile

  override def openApiJsonFile(): File = config.buildToolsDir().resolve("openapi.json").toFile

  override def pnpmEnable(): Boolean = false

  override def bunEnable(): Boolean = false

  override def useGlobalPnpm(): Boolean = false

  override def projectBaseDirectory(): Path = config.projectDir()

  override def requireHomeNodeExec(): Boolean = false

  override def nodeFolder(): String = null

  override def servletResourceOutputDirectory(): File = servletResourceOutDir

  override def webpackOutputDirectory(): File = frontendOutputDirectory()

  override def frontendOutputDirectory(): File = new File(servletResourceOutDir, "webapp")

  override def resourcesOutputDirectory(): File =
    new File(servletResourceOutDir.getParentFile, "META-INF/resources")

  override def frontendResourcesDirectory(): File =
    config.resourcesDir().resolve("META-INF/resources/frontend").toFile

  override def generateBundle(): Boolean = true

  override def generateEmbeddableWebComponents(): Boolean = true

  override def optimizeBundle(): Boolean = true

  override def runNpmInstall(): Boolean = true

  override def ciBuild(): Boolean = false

  override def forceProductionBuild(): Boolean = false

  override def compressBundle(): Boolean = true

  override def checkRuntimeDependency(
      groupId: String,
      artifactId: String,
      missingDependencyMessageConsumer: Consumer[String]
  ): Boolean = {
    val absent = !classpath.exists { entry =>
      val name = entry.getFileName.toString
      name.startsWith(artifactId + "-") && name.endsWith(".jar")
    }
    if (absent && missingDependencyMessageConsumer != null) {
      missingDependencyMessageConsumer.accept(
        s"The dependency $groupId:$artifactId has not been found in the project classpath."
      )
    }
    absent
  }

  /** Vaadin resolves this against [[npmFolder]], so it must be relative to it. */
  override def buildFolder(): String =
    config.projectDir().relativize(config.buildToolsDir()).toString

  override def postinstallPackages(): java.util.List[String] = java.util.List.of()

  override def excludePostinstallPackages(): java.util.List[String] = java.util.List.of()

  override def isFrontendHotdeploy(): Boolean = false

  override def skipDevBundleBuild(): Boolean = false

  override def isPrepareFrontendCacheDisabled(): Boolean = true

  override def isReactEnabled(): Boolean = true

  override def applicationIdentifier(): String = config.applicationIdentifier()

  override def frontendExtraFileExtensions(): java.util.List[String] = java.util.List.of()

  override def isNpmExcludeWebComponents(): Boolean = false

  override def isFrontendIgnoreVersionChecks(): Boolean = false

  override def isCommercialBannerEnabled(): Boolean = false
}
