package mill
package scalalib

import mill.api.PathRef
import mill.define.Segment
import mill.scalalib.api.CompilationResult
import mill.scalalib.internal.ModuleUtils
import mill.scalalib.publish.{Dependency, PackagingType, Scope}

trait BomModule extends BomModule.Consumer {
  def compile: T[CompilationResult] = Task {
    val sources = allSourceFiles()
    if (sources.nonEmpty)
      throw new Exception(s"A BomModule cannot have sources")
    CompilationResult(T.dest / "zinc", PathRef(T.dest / "classes"))
  }

  def resources: T[Seq[PathRef]] = Task {
    val value = super.resources()
    if (value.nonEmpty)
      throw new Exception(s"A BomModule cannot have ressources")
    Seq.empty[PathRef]
  }

  def pomPackagingType: String = PackagingType.Pom

  def jar: T[PathRef] = Task {
    (throw new Exception(s"A BomModule doesn't have a JAR")): PathRef
  }
  def docJar: T[PathRef] = Task {
    (throw new Exception(s"A BomModule doesn't have a doc JAR")): PathRef
  }
  def sourceJar: T[PathRef] = Task {
    (throw new Exception(s"A BomModule doesn't have a source JAR")): PathRef
  }
}

object BomModule {

  trait Consumer extends JavaModule with PublishModule {

    /**
     *  BOM dependencies of this module.
     *  This is meant to be overridden to add BOM dependencies.
     *  To read the value, you should use [[bomModuleDepsChecked]] instead,
     *  which uses a cached result which is also checked to be free of cycle.
     *  @see [[bomModuleDepsChecked]]
     */
    def bomModuleDeps: Seq[BomModule] = Seq.empty

    /**
     * Same as [[bomModuleDeps]] but checked to not contain cycles.
     * Prefer this over using [[bomModuleDeps]] directly.
     */
    final def bomModuleDepsChecked: Seq[BomModule] = {
      // trigger initialization to check for cycles
      recBomModuleDeps
      bomModuleDeps
    }

    /** Should only be called from [[bomModuleDepsChecked]] */
    private lazy val recBomModuleDeps: Seq[BomModule] =
      ModuleUtils.recursive[BomModule](
        (millModuleSegments ++ Seq(Segment.Label("bomModuleDeps"))).render,
        null,
        mod => if (mod == null) bomModuleDeps else mod.bomModuleDeps
      )

    def coursierProject: Task[coursier.core.Project] = Task.Anon {
      val proj = super.coursierProject()
      val extraInternalDeps = bomModuleDepsChecked.map { modDep =>
        val dep = coursier.core.Dependency(
          coursier.core.Module(
            coursier.core.Organization("mill-internal"),
            coursier.core.ModuleName(modDep.millModuleSegments.parts.mkString("-")),
            Map.empty
          ),
          "0+mill-internal"
        )
        (coursier.core.Configuration.`import`, dep)
      }
      proj
        .withDependencies(
          extraInternalDeps ++ proj.dependencies
        )
    }

    def transitiveCoursierProjects: Task[Seq[coursier.core.Project]] = Task.Anon {
      val projects = super.transitiveCoursierProjects() ++
        T.traverse(bomModuleDepsChecked)(_.transitiveCoursierProjects)().flatten
      projects.distinct
    }

    def publishXmlBomDeps: Task[Agg[Dependency]] =
      Task.Anon[Agg[Dependency]] {
        val fromBomMods = T.traverse(bomModuleDepsChecked)(m => m.artifactMetadata)().map { a =>
          Dependency(a, Scope.Import)
        }
        Agg(fromBomMods: _*) ++ super.publishXmlBomDeps()
      }
  }
}
