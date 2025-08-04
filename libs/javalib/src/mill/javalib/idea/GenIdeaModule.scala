package mill.javalib.idea

import java.nio.file.Path

import mill.Task
import mill.api.Segments
import mill.api.daemon.internal.idea.{GenIdeaInternalApi, ResolvedModule}
import mill.api.daemon.internal.internal
import mill.api.{ModuleCtx, PathRef}
import mill.javalib.{BoundDep, Dep, JavaModule}
import mill.api.JsonFormatters.given
trait GenIdeaModule extends mill.api.Module with GenIdeaInternalApi {
  private[mill] val jarCollector: PartialFunction[PathRef, Path] = {
    case p if p.path.ext == "jar" => p.path.toNIO
  }

  def javaModuleRef: mill.api.ModuleRef[JavaModule]
  private lazy val javaModule = javaModuleRef()
  private[mill] def allMvnDeps = Task {
    Seq(
      javaModule.coursierDependency,
      javaModule.coursierDependency.withConfiguration(coursier.core.Configuration.provided)
    ).map(BoundDep(_, force = false))
  }

  private[mill] def scalaCompilerClasspath = Task.Anon(Seq.empty[PathRef])

  private[mill] def externalLibraryDependencies = Task {
    javaModule.defaultResolver().classpath(javaModule.mandatoryMvnDeps())
  }

  private[mill] def extDependencies = Task {
    javaModule.resolvedMvnDeps() ++
      Task.traverse(javaModule.transitiveModuleDeps)(_.unmanagedClasspath)().flatten
  }

  private[mill] def extCompileMvnDeps = Task {
    javaModule.defaultResolver().classpath(javaModule.compileMvnDeps())
  }

  private[mill] val extRunMvnDeps = Task.Anon {
    javaModule.resolvedRunMvnDeps()
  }

  private[mill] def externalSources = Task {
    javaModule.millResolver().classpath(allMvnDeps(), sources = true)
  }

  private[mill] def scalacPluginsMvnDeps = Task.Anon(Seq.empty[Dep])

  private[mill] def allScalacOptions = Task.Anon(Seq.empty[String])

  private[mill] def scalaVersion = Task.Anon(Option.empty[String])

  private[mill] def scalacPluginDependencies = Task {
    javaModule.defaultResolver().classpath(scalacPluginsMvnDeps())
  }

  private[mill] def scopedClasspathEntries = Task[Seq[(path: Path, scope: Option[String])]] {
    extDependencies().collect(jarCollector).map(p => (p, None)) ++
      extCompileMvnDeps().collect(jarCollector).map(p => (p, Some("PROVIDED"))) ++
      extRunMvnDeps().collect(jarCollector).map(p => (p, Some("RUNTIME")))
  }

  private[mill] override def genIdeaResolvedModule(
      ideaConfigVersion: Int,
      segments: Segments
  ): Task[ResolvedModule] = Task.Anon {

    // unused, but we want to trigger sources, to have them available (automatically)
    // TODO: make this a separate eval to handle resolve errors
    externalSources()

    val scopedCpEntries = scopedClasspathEntries()
    val pluginClasspath = scalacPluginDependencies().collect(jarCollector)
    val scalacOpts = allScalacOptions()
    val resolvedCompilerCp = scalaCompilerClasspath().map(_.path.toNIO)
    val resolvedLibraryCp = externalLibraryDependencies().map(_.path.toNIO)
    val facets = javaModule.ideaJavaModuleFacets(ideaConfigVersion)()
    val configFileContributions = javaModule.ideaConfigFiles(ideaConfigVersion)()
    val resolvedCompilerOutput = ideaCompileOutput().path.toNIO
    val resolvedScalaVersion = scalaVersion()
    val resources = javaModule.resources().map(_.path.toNIO)
    val generatedSources = javaModule.generatedSources().map(_.path.toNIO)
    val allSources = javaModule.allSources().map(_.path.toNIO)

    ResolvedModule(
      segments = segments,
      scopedCpEntries = scopedCpEntries,
      module = javaModule,
      pluginClasspath = pluginClasspath,
      scalaOptions = scalacOpts,
      scalaCompilerClasspath = resolvedCompilerCp,
      libraryClasspath = resolvedLibraryCp,
      facets = facets,
      configFileContributions = configFileContributions,
      compilerOutput = resolvedCompilerOutput,
      scalaVersion = resolvedScalaVersion,
      resources = resources,
      generatedSources = generatedSources,
      allSources = allSources
    )
  }

  private[mill] override def ideaCompileOutput: Task.Simple[PathRef] = Task(persistent = true) {
    PathRef(Task.dest / "classes")
  }

}

@internal
object GenIdeaModule {
  trait Wrap(javaModule0: JavaModule) extends mill.api.Module {
    override def moduleCtx: ModuleCtx = javaModule0.moduleCtx

    @internal
    object internalGenIdea extends GenIdeaModule {
      def javaModuleRef = mill.api.ModuleRef(javaModule0)
    }
  }
}
