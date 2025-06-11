package mill.scalalib.idea

import mill.Task
import mill.api.Segments
import mill.api.internal.idea.{
  GenIdeaInternalApi,
  IdeaConfigFile,
  JavaFacet,
  ResolvedModule,
  Scoped
}
import mill.api.internal.{EvaluatorApi, internal}
import mill.define.{Discover, ExternalModule, ModuleCtx, PathRef}
import mill.scalalib.{BoundDep, Dep, JavaModule, ScalaModule}

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

    // We keep all BSP-related tasks/state in this sub-module
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

      private def externalDependencies = Task {
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

      private[mill] override def genIdeaMetadata(
          ideaConfigVersion: Int,
          evaluator: EvaluatorApi,
          path: Segments
      ): Task[ResolvedModule] = {

        val (scalacPluginsMvnDeps, allScalacOptions, scalaVersion) = javaModule match {
          case mod: ScalaModule => (
              Task.Anon(mod.scalacPluginMvnDeps()),
              Task.Anon(mod.allScalacOptions()),
              Task.Anon {
                Some(mod.scalaVersion())
              }
            )
          case _ => (
              Task.Anon(Seq[Dep]()),
              Task.Anon(Seq[String]()),
              Task.Anon(None)
            )
        }

        val scalacPluginDependencies = Task.Anon {
          javaModule.defaultResolver().classpath(scalacPluginsMvnDeps())
        }

        val facets = Task.Anon { javaModule.ideaJavaModuleFacets(ideaConfigVersion)() }

        val configFileContributions = Task.Anon {
          javaModule.ideaConfigFiles(ideaConfigVersion)()
        }

        val resources = Task.Anon { javaModule.resources() }
        val generatedSources = Task.Anon { javaModule.generatedSources() }
        val allSources = Task.Anon { javaModule.allSources() }

        Task.Anon {
          val resolvedCp: Seq[Scoped[os.Path]] =
            externalDependencies().map(_.path).map(Scoped(_, None)) ++
              extCompileMvnDeps()
                .map(_.path)
                .map(Scoped(_, Some("PROVIDED"))) ++
              extRunMvnDeps().map(_.path).map(Scoped(_, Some("RUNTIME")))

          // unused, but we want to trigger sources, to have them available (automatically)
          // TODO: make this a separate eval to handle resolve errors
          externalSources()

          val resolvedSp: Seq[PathRef] = scalacPluginDependencies()
          val resolvedCompilerCp: Seq[PathRef] = scalaCompilerClasspath()
          val resolvedLibraryCp: Seq[PathRef] = externalLibraryDependencies()
          val scalacOpts: Seq[String] = allScalacOptions()
          val resolvedFacets: Seq[JavaFacet] = facets()
          val resolvedConfigFileContributions: Seq[IdeaConfigFile] = configFileContributions()
          val resolvedCompilerOutput = ideaCompileOutput()
          val resolvedScalaVersion = scalaVersion()

          ResolvedModule(
            path = path,
            classpath = resolvedCp
              .filter(_.value.ext == "jar")
              .map(s => Scoped(s.value.toNIO, s.scope)),
            module = javaModule,
            pluginClasspath = resolvedSp.map(_.path).filter(_.ext == "jar").map(_.toNIO),
            scalaOptions = scalacOpts,
            scalaCompilerClasspath = resolvedCompilerCp.map(_.path.toNIO),
            libraryClasspath = resolvedLibraryCp.map(_.path.toNIO),
            facets = resolvedFacets,
            configFileContributions = resolvedConfigFileContributions,
            compilerOutput = resolvedCompilerOutput.path.toNIO,
            scalaVersion = resolvedScalaVersion,
            resources = resources().map(_.path.toNIO),
            generatedSources = generatedSources().map(_.path.toNIO),
            allSources = allSources().map(_.path.toNIO)
          )
        }

      }

      private[mill] override def ideaCompileOutput: Task.Simple[PathRef] = Task(persistent = true) {
        PathRef(Task.dest / "classes")
      }

    }
  }
}
