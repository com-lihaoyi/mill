package mill.scalalib

import coursier.Cache
import coursier.maven.MavenRepository
import mill.eval.Result.{Failure, Success}
import mill.eval.{PathRef, Result}
import mill.util.Loose.Agg
import utest._

object ResolveDepsTests extends TestSuite {
  val repos = Seq(Cache.ivy2Local, MavenRepository("https://repo1.maven.org/maven2"))

  def evalDeps(deps: Agg[Dep]): Result[Agg[PathRef]] =
    Lib.resolveDependencies(repos, "2.12.4", "2.12", deps)

  val tests = Tests {
    'resolveValidDeps - {
      val deps = Agg(ivy"com.lihaoyi::pprint:0.5.3")
      val Success(paths) = evalDeps(deps)
      assert(paths.nonEmpty)
    }

    'errOnInvalidOrgDeps - {
      val deps = Agg(ivy"xxx.yyy.invalid::pprint:0.5.3")
      val Failure(errMsg) = evalDeps(deps)
      assert(errMsg.contains("xxx.yyy.invalid"))
    }

    'errOnInvalidVersionDeps - {
      val deps = Agg(ivy"com.lihaoyi::pprint:invalid.version.num")
      val Failure(errMsg) = evalDeps(deps)
      assert(errMsg.contains("invalid.version.num"))
    }

    'errOnPartialSuccess - {
      val deps = Agg(ivy"com.lihaoyi::pprint:0.5.3", ivy"fake::fake:fake")
      val Failure(errMsg) = evalDeps(deps)
      assert(errMsg.contains("fake"))
    }
  }
}
