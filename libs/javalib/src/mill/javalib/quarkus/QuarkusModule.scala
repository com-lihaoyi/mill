package mill.javalib.quarkus

import coursier.core.VariantSelector.ConfigurationBased
import mill.api.PathRef
import mill.{T, Task}
import mill.javalib.{Dep, DepSyntax, JavaModule, PublishModule}
import mill.util.Jvm
import upickle.default.ReadWriter.join

import java.io.File
import java.net.URLClassLoader
import java.util.Properties
import scala.util.Using

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
    super.bomMvnDeps() ++ Seq(
      mvn"io.quarkus.platform:quarkus-bom:${quarkusPlatformVersion()}"
    )
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
    val depRuntime = coursierDependencyTask().withVariantSelector(
      ConfigurationBased(coursier.core.Configuration.runtime)
    )

    val depCompile = coursierDependencyTask().withVariantSelector(
      ConfigurationBased(coursier.core.Configuration.compile)
    )

    val runtimeDeps =
      millResolver().artifacts(Seq(mill.javalib.BoundDep(depRuntime, force = false)))

    def qualifier(d: coursier.core.Dependency) =
      s"${d.module.organization.value}:${d.module.name.value}"

    def wQualifier(d: ApplicationModelWorker.Dependency) =
      s"${d.groupId}:${d.artifactId}"

    def isDirectDep(d: coursier.core.Module): Boolean =
      mvnDeps().exists(dep => dep.dep.module == d)

    val runtimeDepSet = runtimeDeps.detailedArtifacts0.map(da => qualifier(da._1)).toSet

    val quarkusPrecomputedRuntimeDeps = runtimeDeps.detailedArtifacts0.map {
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

    val compileDeps =
      millResolver().artifacts(Seq(mill.javalib.BoundDep(depCompile, force = false)))

    val quarkusCompileDeps =
      compileDeps.detailedArtifacts0.filterNot {
        da =>
          val q = qualifier(da._1)
          runtimeDepSet.contains(q) || deploymentDepsSet.contains(q) || extensionDepsSet.contains(q)
      }.map {
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
   * The resources to include in the Quarkus Application Model
   */
  def quarkusBuildResources: T[PathRef] = Task {
    val dir = Task.dest

    resources().foreach { res =>
      if (os.exists(res.path)) {
        os.list(res.path).foreach(p => os.copy.into(p, dir))
      }
    }
    PathRef(dir)
  }

  /**
   * Quarkus scans certain directory structures (e.g. for
   * integration tests) so we place the compiled classes as expected in a
   * fresh directory
   */
  def quarkusBuildDirectory: T[PathRef] = Task {
    val compilePath = compile().classes.path
    val targetDir = quarkusAppMode() match {
      case ApplicationModelWorker.AppMode.App =>
        "main"
      case ApplicationModelWorker.AppMode.Test =>
        "test"
    }
    val buildDir = Task.dest / "classes/java" / targetDir
    os.makeDir.all(buildDir)
    os.list(compilePath).foreach(p => os.copy.into(p, buildDir))
    PathRef(buildDir)
  }

  override def localRunClasspath: T[Seq[PathRef]] = Task {
    resources() ++ Seq(quarkusBuildDirectory())
  }

  /**
   * The module data to pass to the quarkus ApplicationModel
   */
  def quarkusModuleData: T[Seq[ApplicationModelWorker.ModuleData]] = Task {
    Seq(
      ApplicationModelWorker.ModuleData(
        quarkusModuleClassifier(),
        ApplicationModelWorker.Source(sources().head.path, quarkusBuildDirectory().path),
        ApplicationModelWorker.Source(resources().head.path, quarkusBuildResources().path)
      )
    )
  }

  def transitiveQuarkusModuleData: T[Seq[ApplicationModelWorker.ModuleData]] = Task {
    val t = Task.sequence(moduleDepsChecked.collect {
      case module: QuarkusModule => module.quarkusModuleData
    })()

    quarkusModuleData() ++ t.flatten
  }

  def quarkusAppModel: T[ApplicationModelWorker.AppModel] = Task {
    ApplicationModelWorker.AppModel(
      projectRoot = outer.moduleDir,
      buildDir = outer.compile().classes.path,
      buildFile = quarkusMillBuildFile().path,
      quarkusVersion = quarkusPlatformVersion(),
      groupId = artifactGroupId(),
      artifactId = artifactId(),
      version = artifactVersion(),
      moduleData = transitiveQuarkusModuleData(),
      boms = bomMvnDeps().map(_.formatted),
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
   * Make -parameters always present.
   * This is required for Quarkus Qute Checked Templates and
   * improved Reflection-based dependency injection.
   */
  override def mandatoryJavacOptions: T[Seq[String]] = Task {
    super.mandatoryJavacOptions() ++ Seq("-parameters")
  }

  /**
   * The properties for building a jar-based Quarkus App.
   */
  def quarkusJarBuildProperties: T[Map[String, String]] = Task {
    Map(
      "quarkus.native.enabled" -> "false"
    )
  }

  /**
   * Java home is required for native builds as we need to point to a GraalVM distribution.
   * This task will fail if javaHome is not configured.
   */
  private def nativeJavaHome: T[PathRef] = Task {
    javaHome() match {
      case Some(p) => p
      case None =>
        Task.fail(
          "javaHome is not configured but required for native builds.\n" +
            "Set `jvmVersion` (or override `javaHome`) to point to a GraalVM distribution."
        )
    }
  }

  /**
   * The properties for building a native Quarkus App.
   * For more options see [[https://quarkus.io/guides/building-native-image#configuration-reference]]
   */
  def quarkusNativeBuildProperties: T[Map[String, String]] = Task {
    val home = nativeJavaHome().path.toString
    Map(
      "quarkus.package.jar.enabled" -> "false",
      "quarkus.native.enabled" -> "true",
      "quarkus.native.graalvm-home" -> home,
      "quarkus.native.java-home" -> home
    )
  }

  private def writeBuildPropertiesFile(destDir: os.Path, props: Map[String, String]): PathRef = {
    val file = destDir / "quarkus-build.properties"
    val properties = new Properties()
    props.foreach { case (key, value) => properties.put(key, value) }
    Using(os.write.outputStream(file))(out =>
      properties.store(out, "Generated build properties by Mill")
    )
    PathRef(file)
  }

  def quarkusJarBuildPropertiesFile: T[PathRef] = Task {
    writeBuildPropertiesFile(Task.dest, quarkusJarBuildProperties())
  }

  def quarkusNativeBuildPropertiesFile: T[PathRef] = Task {
    writeBuildPropertiesFile(Task.dest, quarkusNativeBuildProperties())
  }

  /**
   * A quarkus app built only with the jar packaging
   */
  def quarkusApp: T[ApplicationModelWorker.QuarkusApp] = Task {
    val dest = Task.dest / "quarkus"
    os.makeDir.all(dest)
    quarkusApplicationModelWorker().quarkusBootstrapApplication(
      quarkusSerializedAppModel().path,
      dest,
      jar().path,
      quarkusJarBuildPropertiesFile().path
    )
  }

  /**
   * A native quarkus app built with the native image packaging
   */
  def quarkusNativeApp: T[ApplicationModelWorker.QuarkusApp] = Task {
    val dest = Task.dest / "quarkus-native"
    os.makeDir.all(dest)
    quarkusApplicationModelWorker().quarkusBootstrapApplication(
      quarkusSerializedAppModel().path,
      dest,
      jar().path,
      quarkusNativeBuildPropertiesFile().path
    )
  }

  def quarkusNativePathOpt: T[Option[PathRef]] = Task {
    quarkusNativeApp().nativePath
  }

  def quarkusNativePath: T[PathRef] = Task {
    quarkusNativePathOpt().getOrElse(
      Task.fail("No native image output was produced")
    )
  }

  def quarkusRunJarOpt: T[Option[PathRef]] = Task {
    quarkusApp().runJar
  }

  /**
   * The quarkus-run.jar which is a standalone fast jar
   * created by QuarkusBootstrap using the generated ApplicationModel
   * generated from the [[quarkusSerializedAppModel]]
   * @return the path of the quarkus-run.jar
   */
  def quarkusRunJar: T[PathRef] = Task {
    quarkusRunJarOpt().getOrElse(
      Task.fail("No quarkus-run.jar was produced")
    )
  }

  trait QuarkusTests extends QuarkusModule, JavaTests {

    override def quarkusPlatformVersion: T[String] = outer.quarkusPlatformVersion()
    override def artifactId: T[String] = outer.artifactId()
    override def artifactGroupId: T[String] = outer.artifactGroupId()
    override def artifactVersion: T[String] = outer.artifactVersion()

    override def runClasspath: T[Seq[PathRef]] = super.quarkusUnprocessedRunClasspath()

    override def quarkusModuleClassifier: T[ApplicationModelWorker.ModuleClassifier] =
      ApplicationModelWorker.ModuleClassifier.Tests

    override def quarkusAppMode: T[ApplicationModelWorker.AppMode] = Task {
      ApplicationModelWorker.AppMode.Test
    }

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
      Seq(
        s"-Dquarkus-internal-test.serialized-app-model.path=${quarkusSerializedAppModel().path}",
        // Configure Log Manager and add the required opens/exports
        // See https://github.com/quarkusio/quarkus/blob/main/devtools/gradle/gradle-application-plugin/src/test/java/io/quarkus/gradle/tasks/JvmArgsConfigTest.java
        "-Djava.util.logging.manager=org.jboss.logmanager.LogManager",
        "--add-opens=java.base/java.lang.invoke=ALL-UNNAMED",
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-exports=java.base/jdk.internal.module=ALL-UNNAMED"
      )
    }

  }

  trait QuarkusNativeTest extends QuarkusTests {

    override def quarkusModuleClassifier: T[ApplicationModelWorker.ModuleClassifier] =
      ApplicationModelWorker.ModuleClassifier.NativeTests

    override def forkArgs: T[Seq[String]] = Task {
      Seq(
        s"-Dbuild.output.directory=${outer.quarkusNativeApp().buildOutput.path}",
        s"-Dnative.image.path=${outer.quarkusNativePath().path}"
      ) ++ super.forkArgs()
    }
  }

  trait QuarkusJunit extends QuarkusTests {

    override def testFramework: T[String] = Task {
      "com.github.sbt.junit.jupiter.api.JupiterFramework"
    }

    override def mandatoryMvnDeps: T[Seq[Dep]] = Task {
      Seq(
        mvn"${mill.javalib.api.Versions.jupiterInterface}",
        mvn"io.quarkus:quarkus-junit"
      )
    }

  }
}
