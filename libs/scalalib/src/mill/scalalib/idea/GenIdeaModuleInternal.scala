package mill.scalalib.idea

import mill.Task
import mill.api.Segments
import mill.api.internal.idea.{
  GenIdeaModuleInternalApi,
  IdeaConfigFile,
  JavaFacet,
  ResolvedModule,
  Scoped
}
import mill.api.internal.{EvaluatorApi, internal}
import mill.define.{Discover, ExternalModule, ModuleCtx, PathRef}
import mill.scalalib.{BoundDep, Dep, JavaModule, ScalaModule}

@internal
private[mill] object GenIdeaModuleInternal extends ExternalModule {

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
    object internalGenIdeaModule extends mill.define.Module with GenIdeaModuleInternalApi {

      private[mill] override def genIdeaMetadata(
          ideaConfigVersion: Int,
          evaluator: EvaluatorApi,
          path: Segments
      ): Task[ResolvedModule] = {
        val mod = javaModule
        val jm = javaModule

        // same as input of resolvedMvnDeps
        val allMvnDeps =
          Task.Anon {
            Seq(
              mod.coursierDependency,
              mod.coursierDependency.withConfiguration(coursier.core.Configuration.provided)
            ).map(BoundDep(_, force = false))
          }

        val scalaCompilerClasspath = mod match {
          case sm: ScalaModule => Task.Anon(sm.scalaCompilerClasspath())
          case _ => emptyPathRefs
        }

        val externalLibraryDependencies = Task.Anon {
          jm.defaultResolver().classpath(jm.mandatoryMvnDeps())
        }

        val externalDependencies = Task.Anon {
          jm.resolvedMvnDeps() ++
            Task.traverse(jm.transitiveModuleDeps)(_.unmanagedClasspath)().flatten
        }

        val extCompileMvnDeps = Task.Anon {
          jm.defaultResolver().classpath(jm.compileMvnDeps())
        }

        val extRunMvnDeps = Task.Anon {
          jm.resolvedRunMvnDeps()
        }

        val externalSources = Task.Anon {
          jm.millResolver().classpath(allMvnDeps(), sources = true)
        }

        val (scalacPluginsMvnDeps, allScalacOptions, scalaVersion) = mod match {
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
          jm.defaultResolver().classpath(scalacPluginsMvnDeps())
        }

        val facets = Task.Anon { jm.ideaJavaModuleFacets(ideaConfigVersion)() }

        val configFileContributions = Task.Anon {
          mod.ideaConfigFiles(ideaConfigVersion)()
        }

        val resources = Task.Anon { jm.resources() }
        val generatedSources = Task.Anon { jm.generatedSources() }
        val allSources = Task.Anon { jm.allSources() }

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
            module = mod,
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
