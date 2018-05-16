package mill.scalalib.dependency

import ammonite.ops.pwd
import coursier.Cache
import coursier.maven.MavenRepository
import mill.T
import mill.define._
import mill.eval.Evaluator
import mill.scalalib.{Dep, JavaModule, Lib}
import mill.util.Ctx.{Home, Log}
import mill.util.{Loose, Strict}

object Dependency extends ExternalModule {

  def updates(ev: Evaluator[Any]) = T.command {
    DependencyUpdatesImpl(implicitly, ev.rootModule, ev.rootModule.millDiscover)
  }

  implicit def millScoptEvaluatorReads[T] = new mill.main.EvaluatorScopt[T]()
  lazy val millDiscover = Discover[this.type]
}

object DependencyUpdatesImpl {

  def apply(ctx: Log with Home,
            rootModule: BaseModule,
            discover: Discover[_]): Unit = {
    println(s"Dependency updates")

    val evaluator =
      new Evaluator(ctx.home, pwd / 'out, pwd / 'out, rootModule, ctx.log)

    def eval[T](e: Task[T]): T =
      evaluator.evaluate(Strict.Agg(e)).values match {
        case Seq()     => throw new NoSuchElementException
        case Seq(e: T) => e
      }

    def evalOrElse[T](e: Task[T], default: => T): T =
      evaluator.evaluate(Strict.Agg(e)).values match {
        case Seq()     => default
        case Seq(e: T) => e
      }

    val javaModules = rootModule.millInternal.modules.collect {
      case javaModule: JavaModule => javaModule
    }
    println(s"javaModules: $javaModules")

    val resolvedDependencies = javaModules.map { javaModule =>
      val depToDependency = eval(javaModule.resolveCoursierDependency)
      val deps = evalOrElse(javaModule.ivyDeps, Loose.Agg.empty[Dep])

      val (dependencies, resolution) =
        Lib.resolveDependenciesMetadata(javaModule.repositories,
                                        depToDependency,
                                        deps)

      (javaModule, dependencies, resolution)
    }

    val x = resolvedDependencies.map {
      case (javaModule, dependencies, resolution) =>
        val mavenRepos = javaModule.repositories.collect {
          case mavenRepo: MavenRepository => mavenRepo
        }

        val fetch = Cache.fetch()

        val versionsByDependency = dependencies.map { dependency =>
          val mod = dependency.moduleVersion._1
          val allVersions = mavenRepos.flatMap { mavenRepo =>
            (
              mavenRepo.versions(mod, fetch).run.unsafePerformSync orElse
                mavenRepo.versionsFromListing(mod, fetch).run.unsafePerformSync
            ).toList
          }
          val versions = allVersions.flatMap(_.available).toSet
          (dependency, versions)
        }

        (javaModule, versionsByDependency)
    }

    x.foreach {
      case (javaModule, versionsByDependency) =>
        println("----------")
        println(javaModule)
        println("----------")
        versionsByDependency.foreach {
          case (dependency, versions) =>
            println(dependency)
            println(versions)
            println()
        }
        println()
    }

  }
}
