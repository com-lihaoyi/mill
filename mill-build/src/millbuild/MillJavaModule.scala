package millbuild

import build_.package_ as build
import coursier.MavenRepository
import coursier.VersionConstraint
import mill.PathRef
import mill.T
import mill.Task
import mill.scalalib.Dep
import mill.scalalib.JavaModule

import java.io.File

import scala.util.Properties

trait MillJavaModule extends JavaModule {

  // Test setup
  def localTestOverride =
    Task { (s"com.lihaoyi-${artifactId()}", localTestOverridePaths().map(_.path).mkString("\n")) }

  def testArgs: T[Seq[String]] = Task {
    // Workaround for Zinc/JNA bug
    // https://github.com/sbt/sbt/blame/6718803ee6023ab041b045a6988fafcfae9d15b5/main/src/main/scala/sbt/Main.scala#L130
    val jnaArgs = Seq("-Djna.nosys=true")
    val userLang =
      if (Properties.isMac || Properties.isWin) Seq("-Duser.language=en")
      else Nil
    jnaArgs ++ userLang
  }
  def localTestOverridePaths =
    Task { upstreamAssemblyClasspath() ++ Seq(compile().classes) ++ resources() }

  def localTestExtraModules: Seq[MillJavaModule] = Nil
  def localTestRepositories: T[Seq[PathRef]] = {

    def recursive[T](start: T, deps: T => Seq[T]): Seq[T] = {

      @annotation.tailrec
      def rec(
          seenModules: Set[T],
          acc: List[T],
          toAnalyze: List[T]
      ): List[T] =
        toAnalyze match {
          case Nil => acc
          case cand :: remaining =>
            if (seenModules.contains(cand))
              rec(
                seenModules,
                acc,
                toAnalyze = remaining
              )
            else
              rec(
                seenModules + cand,
                cand :: acc,
                deps(cand).toList ::: remaining
              )
        }

      rec(Set(), Nil, start :: Nil).reverse
    }

    val allModules = recursive[MillJavaModule](
      this,
      m => {
        val localTestExtraModules0 = m match {
          case m0: MillJavaModule => m0.localTestExtraModules
          case _ => Nil
        }
        (m.moduleDeps ++ m.runModuleDeps ++ localTestExtraModules0).collect {
          case m0: MillJavaModule => m0
        }
      }
    )
    Task {
      Task.traverse(allModules) {
        case m: MillPublishJavaModule => m.stagePublish.map(Seq(_))
        case _ => Task.Anon(Nil)
      }().flatten
    }
  }

  def testMvnDeps: T[Seq[Dep]] = Seq(Deps.TestDeps.utest)
  def testForkEnv: T[Map[String, String]] = forkEnv() ++ localTestOverridesEnv()
  def testModuleDeps: Seq[JavaModule] =
    if (this == build.core.api) Seq(build.core.api)
    else Seq(this, build.core.api.test)

  def localTestOverridesEnv = Task {
    val localRepos = localTestRepositories()
      .map(_.path.toString)
      .mkString(File.pathSeparator)
    Seq("MILL_LOCAL_TEST_REPO" -> localRepos)
  }

  def repositoriesTask = Task.Anon {
    super.repositoriesTask() ++
      Seq(MavenRepository("https://oss.sonatype.org/content/repositories/releases"))
  }

  def mapDependencies: Task[coursier.Dependency => coursier.Dependency] = Task.Anon {
    super.mapDependencies().andThen { dep =>
      forcedVersions.find(f =>
        f.dep.module.organization.value == dep.module.organization.value &&
          f.dep.module.name.value == dep.module.name.value
      ).map { forced =>
        val newDep = dep.withVersionConstraint(VersionConstraint(forced.version))
        Task.log.debug(
          s"Forcing version of ${dep.module} from ${dep.versionConstraint.asString} to ${newDep.versionConstraint.asString}"
        )
        newDep
      }.getOrElse(dep)
    }
  }
  val forcedVersions: Seq[Dep] = Deps.transitiveDeps ++ Seq(
    Deps.jline,
    Deps.jna
  )

  def javadocOptions = super.javadocOptions() ++ Seq(
    // Disable warnings for missing documentation comments or tags (for example,
    // a missing comment or class, or a missing @return tag or similar tag on a method).
    // We have many methods without JavaDoc comments, so those warnings are useless
    // and significantly clutter the output.
    "-Xdoclint:all,-missing"
  )
}
