package mill.javalib.quarkus

import coursier.core.VariantSelector.ConfigurationBased
import mill.api.PathRef
import mill.{T, Task}
import mill.javalib.{Dep, DepSyntax, JavaModule, PublishModule}
import mill.util.Jvm
import upickle.default.ReadWriter.join

import java.io.File
import java.net.URLClassLoader

@mill.api.experimental
trait QuarkusModule extends JavaModule { outer =>

  /**
   * The version of the quarkus platform (e.g. 3.31.2). Used for
   * setting the `io.quarkus.platform:quarkus-bom` version
   * It's used for creating the quarkus Application Model and bootstrapping this Quarkus module
   *
   * For the latest version check [[https://quarkus.io/]]
   */
  def quarkusPlatformVersion: T[String]

  /**
   * If this is a [[PublishModule]] then group id is derived from [[PublishModule.pomSettings]]
   * otherwise it needs to be setup by the user.
   */
  def artifactGroupId: T[String] = this match {
    case m: PublishModule =>
      Task {
        m.pomSettings().organization
      }
    case _ =>
      Task {
        Task.fail(
          "The moduleGroupId is not set. Please override the moduleGroupId so quarkus can generate the application model for this module"
        )
      }
  }

  /**
   * If this is a [[PublishModule]] then artifact version is derived from [[PublishModule.publishVersion]]
   * otherwise it needs to be setup by the user.
   *
   * It's used for creating the quarkus Application Model and bootstrapping this Quarkus module
   */
  def artifactVersion: T[String] = this match {
    case m: PublishModule =>
      Task {
        m.publishVersion()
      }
    case _ =>
      Task {
        Task.fail(
          "The artifactVersion is not set. Please override the artifactVersion so quarkus can generate the application model for this module"
        )
      }
  }

  /**
   * The artifact id used to Quarkus bootstrap this module.
   * It needs to be set and a non-empty String for the Quarkus application model serialization to work!
   */
  def artifactId: T[String] = Task {
    Option(super.artifactId()).filterNot(_.isEmpty)
      .getOrElse(Task.fail(
        "The artifactVersion is not set. Please override the artifactVersion so quarkus can generate the application model for this module"
      ))
  }

  override def bomMvnDeps: T[Seq[Dep]] = Task {
    val boms = super.bomMvnDeps() ++ Seq(
      mvn"io.quarkus.platform:quarkus-bom:${quarkusPlatformVersion()}"
    )
    boms.distinct
  }

  /**
   * Dependencies for the Quarkus Bootstrap process
   * needed for [[quarkusApplicationModelWorker]]
   */
  def quarkusBootstrapDeps: T[Seq[Dep]] = Task {
    Seq(
      mvn"io.quarkus:quarkus-bootstrap-core",
      mvn"io.quarkus:quarkus-bootstrap-app-model",
      mvn"io.quarkus:quarkus-core-deployment"
    )
  }

  /**
   * The native image for building this Quarkus module.
   * Can be mandrel, graalvm or a full image path.
   *
   * For more info see [[https://quarkus.io/guides/building-native-image#background]]
   */
  def quarkusNativeImage: T[String] = Task {
    "mandrel"
  }

  private[QuarkusModule] def quarkusUnprocessedRunClasspath: T[Seq[PathRef]] = Task {
    super.runClasspath()
  }

  /**
   * Quarkus builds its own run classpath and manages it
   * via the launcher (quarkus-run.jar) which handles
   * running the application.
   */
  override def runClasspath: T[Seq[PathRef]] = Task {
    Seq(quarkusRunJar())
  }

  def quarkusBootstrapResolvedDeps: T[Seq[PathRef]] = Task {
    defaultResolver().classpath(
      quarkusBootstrapDeps() ++ Seq(Dep.millProjectModule("mill-libs-javalib-quarkus-worker")),
      boms = allBomDeps()
    )
  }

  /**
   * Quarkus builds its own run classpath and manages it
   * via the launcher (quarkus-run.jar) which handles
   * and doesn't need a main class. However, for mill run to work
   * we need to put the Quarkus entrypoint here which is `io.quarkus.bootstrap.runner.QuarkusEntryPoint`
   */
  override def finalMainClass: T[String] = "io.quarkus.bootstrap.runner.QuarkusEntryPoint"

  def quarkusApplicationModelWorkerClassloader: Task.Worker[URLClassLoader] = Task.Worker {
    Jvm.createClassLoader(
      quarkusBootstrapResolvedDeps().map(_.path),
      parent = getClass.getClassLoader
    )
  }

  /**
   * The worker which provides the Quarkus Bootstrap process for creating
   * the quarkus Application Model and building the Application itself.
   *
   * This model is used by the QuarkusBootstrap to derive the dependencies
   * and the build steps (e.g. packaging a jar or a native app), checking
   * which dependencies are runtime, which are deployable etc.
   *
   * See also [[quarkusDependencies]] and [[quarkusApplicationModelWorker]] for following the
   * full implementation steps.
   */
  def quarkusApplicationModelWorker: Task.Worker[ApplicationModelWorker] = Task.Worker {
    quarkusApplicationModelWorkerClassloader().loadClass(
      "mill.javalib.quarkus.ApplicationModelWorkerImpl"
    )
      .getDeclaredConstructor()
      .newInstance()
      .asInstanceOf[ApplicationModelWorker]
  }

  /**
   * These are the application dependencies that Quarkus needs to know about
   * for bootstrapping. They generally are in 3 categories:
   * 1. Runtime applications
   * 2. Deployment applications (that are also runtime)
   * 3. Compile applications
   * In addition, other helpful flags, help the [[ApplicationModelWorker]] to
   * flag these dependencies correctly, such as marking top level artifacts (i.e. direct dependencies).
   *
   * This mechanism is not fully implemneted yet, and only works for a single module.
   */
  def quarkusDependencies: T[Seq[ApplicationModelWorker.Dependency]] = Task {
    val runtimeConfig = coursier.core.Configuration.runtime

    val depRuntime = coursierDependencyTask().withVariantSelector(
      ConfigurationBased(runtimeConfig)
    )

    val resolvedDepArtifacts =
      millResolver().artifacts(Seq(mill.javalib.BoundDep(depRuntime, force = false)))

    def qualifier(d: coursier.core.Dependency) =
      s"${d.module.organization.value}:${d.module.name.value}"

    def wQualifier(d: ApplicationModelWorker.Dependency) =
      s"${d.groupId}:${d.artifactId}"

    def isDirectDep(d: coursier.core.Module): Boolean =
      mvnDeps().exists(dep => dep.dep.module == d)

    val runtimeDeps = resolvedDepArtifacts.detailedArtifacts0
      .filter(_._1.variantSelector.asConfiguration.contains(runtimeConfig))

    val runtimeDepSet = runtimeDeps
      .map(da => qualifier(da._1)).toSet

    val quarkusPrecomputedRuntimeDeps = runtimeDeps
      .map {
        case (dependency, _, _, file) =>
          ApplicationModelWorker.Dependency(
            groupId = dependency.module.organization.value,
            artifactId = dependency.module.name.value,
            version = dependency.versionConstraint.asString,
            resolvedPath = os.Path(file),
            isRuntime = true,
            isDeployment = false,
            isTopLevelArtifact = isDirectDep(dependency.module),
            hasExtension = false
          )
      }

    val depsWithExtensions = quarkusApplicationModelWorker().quarkusDeploymentDependencies(
      quarkusPrecomputedRuntimeDeps
    )

    val extensionDepsSet = depsWithExtensions.map(wQualifier).toSet

    // TODO this is a hack, there's a util to do this
    val deploymentMvnDeps = depsWithExtensions.map(d =>
      mvn"${d.groupId}:${d.artifactId}-deployment:${d.version}"
    )

    val deploymentDeps = millResolver().artifacts(
      deploymentMvnDeps
    )

    val deploymentDepsSet = deploymentDeps.detailedArtifacts0.map(da => qualifier(da._1)).toSet

    val quarkusDeploymentDeps = deploymentDeps.detailedArtifacts0.map {
      case (dependency, _, _, file) =>
        ApplicationModelWorker.Dependency(
          groupId = dependency.module.organization.value,
          artifactId = dependency.module.name.value,
          version = dependency.versionConstraint.asString,
          resolvedPath = os.Path(file),
          isRuntime = runtimeDepSet.contains(qualifier(dependency)),
          isDeployment = true,
          isTopLevelArtifact = isDirectDep(dependency.module),
          hasExtension = extensionDepsSet.contains(qualifier(dependency))
        )
    }

    val quarkusRuntimeDeps = quarkusPrecomputedRuntimeDeps.filterNot(d =>
      deploymentDepsSet.contains(wQualifier(d))
    )

    val compileDeps = resolvedDepArtifacts.detailedArtifacts0
      .filterNot(_._1.variantSelector.asConfiguration.contains(runtimeConfig))
      .filterNot {
        da =>
          val q = qualifier(da._1)
          runtimeDepSet.contains(q) || deploymentDepsSet.contains(q) || extensionDepsSet.contains(q)
      }

    val quarkusCompileDeps =
      compileDeps.map {
        case (dependency, _, _, file) =>
          ApplicationModelWorker.Dependency(
            groupId = dependency.module.organization.value,
            artifactId = dependency.module.name.value,
            version = dependency.versionConstraint.asString,
            resolvedPath = os.Path(file),
            isRuntime = false,
            isDeployment = false,
            isTopLevelArtifact = isDirectDep(dependency.module),
            hasExtension = false
          )
      }

    quarkusRuntimeDeps ++ quarkusCompileDeps ++ quarkusDeploymentDeps
  }

  /**
   * The quarkus Application Model requires a build file. While
   * in Mill, we don't let Quarkus resolve any dependencies (quarkus itself
   * does not have mill support to understand what's going on) this exists
   * to keep the Quarkus Application model serialization from complaining.
   *
   * For now, we pass a dummy file.
   */
  def quarkusMillBuildFile: Task.Simple[PathRef] = Task {
    val dummyFile = Task.dest / "dummy_build_file"
    os.write(dummyFile, "dummy")
    PathRef(dummyFile)
  }

  def quarkusModuleClassifier: T[ApplicationModelWorker.ModuleClassifier] = Task {
    ApplicationModelWorker.ModuleClassifier.Main
  }

  /**
   * The mode in which to build this Quarkus App
   */
  def quarkusAppMode: T[ApplicationModelWorker.AppMode] = Task {
    ApplicationModelWorker.AppMode.App
  }

  /**
   * Dummy placeholder for Quarkus application model resources
   */
  def quarkusBuildResources: T[PathRef] = Task {
    val dir = Task.dest
    PathRef(dir)
  }

  /**
   * The module data to pass to the quarkus ApplicationModel
   */
  def quarkusModuleData: T[Seq[ApplicationModelWorker.ModuleData]] = Task {
    Seq(
      ApplicationModelWorker.ModuleData(
        quarkusModuleClassifier(),
        ApplicationModelWorker.Source(sources().head.path, compile().classes.path),
        ApplicationModelWorker.Source(resources().head.path, quarkusBuildResources().path)
      )
    )
  }

  def quarkusAppModel: T[ApplicationModelWorker.AppModel] = Task {
    ApplicationModelWorker.AppModel(
      projectRoot = moduleDir,
      buildDir = compile().classes.path,
      buildFile = quarkusMillBuildFile().path,
      quarkusVersion = quarkusPlatformVersion(),
      groupId = artifactGroupId(),
      artifactId = artifactId(),
      version = artifactVersion(),
      moduleData = quarkusModuleData(),
      boms = bomMvnDeps().distinct.map(_.formatted),
      dependencies = quarkusDependencies(),
      nativeImage = quarkusNativeImage(),
      appMode = quarkusAppMode()
    )
  }

  def quarkusSerializedAppModel: T[PathRef] = Task {
    val modelPath = quarkusApplicationModelWorker().quarkusGenerateApplicationModel(
      quarkusAppModel(),
      Task.dest
    )
    PathRef(modelPath)

  }

  /**
   * The quarkus-run.jar which is a standalone fast jar
   * created by QuarkusBootstrap using the generated ApplicationModel
   * generated from the [[quarkusSerializedAppModel]]
   * @return the path of the quarkus-run.jar
   */
  def quarkusRunJar: T[PathRef] = Task {
    val dest = Task.dest / "quarkus"
    os.makeDir.all(dest)
    val jarPath = quarkusApplicationModelWorker().quarkusBootstrapApplication(
      quarkusSerializedAppModel().path,
      dest / "quarkus-run.jar", // TODO use quarkus utility function
      jar().path
    )

    PathRef(jarPath)
  }

  trait QuarkusTests extends QuarkusModule, JavaTests {

    override def quarkusPlatformVersion: T[String] = outer.quarkusPlatformVersion()
    override def artifactId: T[String] = outer.artifactId()
    override def artifactGroupId: T[String] = outer.artifactGroupId()
    override def artifactVersion: T[String] = outer.artifactVersion()

    override def quarkusModuleData: T[Seq[ApplicationModelWorker.ModuleData]] =
      outer.quarkusModuleData() ++ super.quarkusModuleData()

    override def runClasspath: T[Seq[PathRef]] = super.quarkusUnprocessedRunClasspath()

    override def quarkusModuleClassifier: T[ApplicationModelWorker.ModuleClassifier] =
      ApplicationModelWorker.ModuleClassifier.Tests

    override def quarkusAppMode: T[ApplicationModelWorker.AppMode] = Task {
      ApplicationModelWorker.AppMode.Test
    }

    /**
     * The test model build by [[quarkusSerializedAppModel]] needs to be passed
     * in both the test discovery and running the tests themselves
     */
    def quarkusSerializedAppModelJavaOpts: T[Seq[String]] = Task {
      Seq(
        s"-Dquarkus-internal-test.serialized-app-model.path=${quarkusSerializedAppModel().path}"
      )
    }

    override def testDiscoverRuntimeOptions: T[Seq[String]] = Task {
      quarkusSerializedAppModelJavaOpts() ++ Seq(
        "-cp",
        runClasspath().map(_.path.toString).mkString(File.pathSeparator)
      )
    }

    override def forkArgs: T[Seq[String]] = Task {
      quarkusSerializedAppModelJavaOpts()
    }
  }

  trait QuarkusJunit extends QuarkusTests {

    override def testFramework = "com.github.sbt.junit.jupiter.api.JupiterFramework"

    override def mandatoryMvnDeps: T[Seq[Dep]] = Task {
      Seq(
        mvn"${mill.javalib.api.Versions.jupiterInterface}",
        mvn"io.quarkus:quarkus-junit"
      )
    }

  }
}
