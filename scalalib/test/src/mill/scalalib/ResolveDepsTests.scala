package mill.scalalib

import coursier.Cache
import coursier.maven.MavenRepository
import mill.eval.Result.{Failure, Success}
import mill.eval.{PathRef, Result}
import mill.util.Loose.Agg
import utest._

object ResolveDepsTests extends TestSuite {
  val repos = Seq(Cache.ivy2Local, MavenRepository("https://repo1.maven.org/maven2"))

  def evalDeps(deps: Agg[Dep]): Result[Agg[PathRef]] = Lib.resolveDependencies(repos, "2.12.4", deps)

  val tests = Tests {
    'resolveValidDeps - {
      val deps = Agg(ivy"com.lihaoyi::pprint:0.5.3")
      val Success(paths) = evalDeps(deps)
      assert(paths.nonEmpty)
    }

    'resolveValidDepsWithClassifier - {
      val deps = Agg(ivy"org.lwjgl:lwjgl:3.1.1;classifier=natives-macos")
      val Success(paths) = evalDeps(deps)
      assert(paths.nonEmpty)
      assert(paths.items.next.path.toString.contains("natives-macos"))
    }

    'excludeTransitiveDeps - {
      val deps = Agg(ivy"com.lihaoyi::pprint:0.5.3".exclude("com.lihaoyi" -> "fansi_2.12"))
      val Success(paths) = evalDeps(deps)
      assert(!paths.exists(_.path.toString.contains("fansi_2.12")))
    }

    'excludeTransitiveDepsByOrg - {
      val deps = Agg(ivy"com.lihaoyi::pprint:0.5.3".excludeOrg("com.lihaoyi"))
      val Success(paths) = evalDeps(deps)
      assert(!paths.exists(path => path.path.toString.contains("com/lihaoyi") && !path.path.toString.contains("pprint_2.12")))
    }

    'excludeTransitiveDepsByName - {
      val deps = Agg(ivy"com.lihaoyi::pprint:0.5.3".excludeName("fansi_2.12"))
      val Success(paths) = evalDeps(deps)
      assert(!paths.exists(_.path.toString.contains("fansi_2.12")))
    }

    'errOnInvalidOrgDeps - {
      val deps = Agg(ivy"xxx.yyy.invalid::pprint:0.5.3")
      val Failure(errMsg, _) = evalDeps(deps)
      assert(errMsg.contains("xxx.yyy.invalid"))
    }

    'errOnInvalidVersionDeps - {
      val deps = Agg(ivy"com.lihaoyi::pprint:invalid.version.num")
      val Failure(errMsg, _) = evalDeps(deps)
      assert(errMsg.contains("invalid.version.num"))
    }

    'errOnPartialSuccess - {
      val deps = Agg(ivy"com.lihaoyi::pprint:0.5.3", ivy"fake::fake:fake")
      val Failure(errMsg, _) = evalDeps(deps)
      assert(errMsg.contains("fake"))
    }
  }
}
