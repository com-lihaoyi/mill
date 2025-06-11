package mill.scalalib.idea

import java.nio.file.Path

import mill.Task
import mill.api.Segments
import mill.api.internal.idea.{GenIdeaInternalApi, ResolvedModule}
import mill.api.internal.{EvaluatorApi, internal}
import mill.define.{Discover, ExternalModule, ModuleCtx, PathRef}
import mill.scalalib.{BoundDep, Dep, JavaModule, ScalaModule}
import mill.define.JsonFormatters.given

@internal
private[mill] object GenIdeaInternal extends ExternalModule {

  // Requirement of ExternalModule's
  override protected def millDiscover: Discover = Discover[this.type]

  // Hack-ish way to have some BSP state in the module context
  @internal
  implicit class EmbeddableGenIdeaModuleInternal(javaModule: JavaModule)
      extends mill.define.Module {
    // We act in the context of the module
    override def moduleCtx: ModuleCtx = javaModule.moduleCtx

    private val emptyPathRefs = Task.Anon(Seq.empty[PathRef])
    private val emptyDeps = Task.Anon(Seq.empty[Dep])

    private val jarCollector: PartialFunction[PathRef, Path] = {
      case p if p.path.ext == "jar" => p.path.toNIO
    }

    // We keep all GenIdea-related tasks/state in this sub-module
    @internal
    object internalGenIdea extends mill.define.Module with GenIdeaInternalApi {

      private def allMvnDeps = Task {
        Seq(
          javaModule.coursierDependency,
          javaModule.coursierDependency.withConfiguration(coursier.core.Configuration.provided)
        ).map(BoundDep(_, force = false))
      }

      private val scalaCompilerClasspath = javaModule match {
        case sm: ScalaModule => Task.Anon(sm.scalaCompilerClasspath())
        case _ => emptyPathRefs
      }

      private def externalLibraryDependencies = Task {
        javaModule.defaultResolver().classpath(javaModule.mandatoryMvnDeps())
      }

      private def extDependencies = Task {
        javaModule.resolvedMvnDeps() ++
          Task.traverse(javaModule.transitiveModuleDeps)(_.unmanagedClasspath)().flatten
      }

      private def extCompileMvnDeps = Task {
        javaModule.defaultResolver().classpath(javaModule.compileMvnDeps())
      }

      private val extRunMvnDeps = Task.Anon {
        javaModule.resolvedRunMvnDeps()
      }

      private def externalSources = Task {
        javaModule.millResolver().classpath(allMvnDeps(), sources = true)
      }

      private val scalacPluginsMvnDeps = javaModule match {
        case mod: ScalaModule => mod.scalacPluginMvnDeps
        case _ => emptyDeps
      }

      private val allScalacOptions = javaModule match {
        case mod: ScalaModule => mod.allScalacOptions
        case _ => Task.Anon(Seq[String]())
      }

      private val scalaVersion = javaModule match {
        case mod: ScalaModule => Task.Anon { Some(mod.scalaVersion()) }
        case _ => Task.Anon(None)
      }

      private def scalacPluginDependencies = Task {
        javaModule.defaultResolver().classpath(scalacPluginsMvnDeps())
      }

      private def scopedClasspathEntries = Task[Seq[(path: Path, scope: Option[String])]] {
        extDependencies().collect(jarCollector).map(p => (p, None)) ++
          extCompileMvnDeps().collect(jarCollector).map(p => (p, Some("PROVIDED"))) ++
          extRunMvnDeps().collect(jarCollector).map(p => (p, Some("RUNTIME")))
      }

      private[mill] override def genIdeaResolvedModule(
          ideaConfigVersion: Int,
          evaluator: EvaluatorApi,
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
  }
}
