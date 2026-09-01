package mill.javalib.eclipse

import mill.Task
import mill.api.{ModuleCtx, ModuleRef, PathRef}
import mill.api.daemon.internal.eclipse.{GenEclipseInternalApi, ResolvedModule}
import mill.api.daemon.internal.{KotlinModuleApi, ScalaModuleApi, TaskApi, internal}
import mill.javalib.{BoundDep, JavaModule}
import mill.api.JsonFormatters.given

import java.nio.file.Path

trait GenEclipseModule extends mill.api.Module with GenEclipseInternalApi {
  def javaModuleRef: mill.api.ModuleRef[JavaModule]
  private lazy val javaModule = javaModuleRef()

  // Using Coursier we get all the compile / runtime dependencies to resolve them and directly
  // download their sources as well.
  private[mill] def allCoursierDependencies = Task {
    Seq(
      javaModule.coursierDependencyTask(),
      javaModule.coursierDependencyTask().withConfiguration(coursier.core.Configuration.provided)
    ).map(BoundDep(_, force = false))
  }

  // Use Mill magic to resolve the dependcies with their sources.
  private[mill] def resolveSourcesJars = Task {
    javaModule.millResolver().classpath(allCoursierDependencies(), sources = true)
  }

  // Get Maven dependencies including transitive ones
  private[mill] def extDependencies = Task {
    javaModule.resolvedMvnDeps() ++
      Task.traverse(javaModule.transitiveModuleDeps)(_.unmanagedClasspath)().flatten
  }

  // Get all compile Maven dependencies
  private[mill] def extCompileMvnDeps = Task {
    javaModule.defaultResolver().classpath(javaModule.compileMvnDeps())
  }

  // Get all runtime Maven dependencies
  private[mill] val extRunMvnDeps = Task.Anon {
    javaModule.resolvedRunMvnDeps()
  }

  private[mill] val jarCollector: PartialFunction[PathRef, Path] = {
    case p if p.path.ext == "jar" => p.path.toNIO
  }

  private[mill] def libraryClasspath = Task[Seq[Path]] {
    extDependencies().collect(jarCollector) ++
      extCompileMvnDeps().collect(jarCollector) ++
      extRunMvnDeps().collect(jarCollector)
  }

  private[mill] def nonJdtModuleDependencyClasspath = Task {
    Task.traverse(
      javaModule.moduleDepsChecked.filterNot(isOnlyJavaModule).flatMap { module =>
        module.transitiveModuleCompileModuleDeps :+ module
      }.distinct
    )(_.localClasspath)().flatten.filter(pathRef => os.exists(pathRef.path))
  }

  private[mill] override def genEclipseModuleInformation(): TaskApi[ResolvedModule] = Task.Anon {
    // Resolve all dependencies by their "-sources.jar" archives.
    resolveSourcesJars()

    // Get the sources and resources directories
    val resources = javaModule.resources().map(_.path.toNIO)
    val generatedSources = javaModule.generatedSources().map(_.path.toNIO)
    val allSources = javaModule.allSources().map(_.path.toNIO)

    // Get all the module dependencies that will be translated to Eclipse JDT project dependencies
    val moduleDeps = javaModule.moduleDepsChecked.filter(isOnlyJavaModule).map(_.moduleDirJava)

    // This can contain both JAR archives or folder of classes, etc. Eclipse does not care ^^
    val unmanagedClasspath = javaModule.unmanagedClasspath().map(_.javaPath)

    // This includes JAR dependencies and the compiled output of module types that Eclipse JDT
    // cannot represent as projects.
    val dependencyClasspath =
      libraryClasspath() ++ nonJdtModuleDependencyClasspath().map { pathRef =>
        PathRef.toAbsNioPath(PathRef.toResolvedOsPath(pathRef.path))
      }

    ResolvedModule(
      segments = javaModule.moduleSegments,
      module = javaModule,
      allSources = generatedSources ++ allSources ++ resources,
      allModuleDependencies = moduleDeps,
      allLibraryDependencies = unmanagedClasspath ++ dependencyClasspath
    )
  }

  private def isOnlyJavaModule(module: JavaModule): Boolean =
    !module.isInstanceOf[ScalaModuleApi] && !module.isInstanceOf[KotlinModuleApi]
}

@internal
object GenEclipseModule {
  trait Wrap(javaModule0: JavaModule) extends mill.api.Module {
    override def moduleCtx: ModuleCtx = javaModule0.moduleCtx

    @internal
    object internalGenEclipse extends GenEclipseModule {
      def javaModuleRef: ModuleRef[JavaModule] = mill.api.ModuleRef(javaModule0)
    }
  }
}
