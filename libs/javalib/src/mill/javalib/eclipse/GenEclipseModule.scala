package mill.javalib.eclipse

import mill.Task
import mill.api.{ModuleCtx, ModuleRef, PathRef}
import mill.api.daemon.internal.eclipse.{GenEclipseInternalApi, ResolvedModule}
import mill.api.daemon.internal.{TaskApi, internal}
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
      javaModule.coursierDependency,
      javaModule.coursierDependency.withConfiguration(coursier.core.Configuration.provided)
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

  private[mill] override def genEclipseModuleInformation(): TaskApi[ResolvedModule] = Task.Anon {
    // Resolve all dependencies by their "-sources.jar" archives.
    resolveSourcesJars()

    // Get the sources and resources directories
    val resources = javaModule.resources().map(_.path.toNIO)
    val generatedSources = javaModule.generatedSources().map(_.path.toNIO)
    val allSources = javaModule.allSources().map(_.path.toNIO)

    // Get all the module dependencies that will be translated to Eclipse JDT project dependencies
    val moduleDeps = javaModule.moduleDepsChecked.map(_.moduleDirJava)

    // This can contain both JAR archives or folder of classes, etc. Eclipse does not care ^^
    val unmanagedClasspath = javaModule.unmanagedClasspath().map(_.javaPath)

    // This will be all Jar archive dependencies both direct and transitive
    val dependencyClasspath = libraryClasspath()

    ResolvedModule(
      segments = javaModule.moduleSegments,
      module = javaModule,
      allSources = generatedSources ++ allSources ++ resources,
      allModuleDependencies = moduleDeps,
      allLibraryDependencies = unmanagedClasspath ++ dependencyClasspath
    )
  }
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
