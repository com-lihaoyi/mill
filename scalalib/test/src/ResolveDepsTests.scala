package mill.scalalib

import coursier.maven.MavenRepository
import mill.api.Result.{Failure, Success}
import mill.api.{PathRef, Result}
import mill.api.Loose.Agg
import utest._

object ResolveDepsTests extends TestSuite {
  val scala212Version = sys.props.getOrElse("TEST_SCALA_2_12_VERSION", ???)
  val repos =
    Seq(coursier.LocalRepositories.ivy2Local, MavenRepository("https://repo1.maven.org/maven2"))

  def evalDeps(deps: Agg[Dep]): Result[Agg[PathRef]] = Lib.resolveDependencies(
    repos,
    Lib.depToDependency(_, scala212Version, ""),
    deps
  )

  val tests = Tests {
    "resolveValidDeps" - {
      val deps = Agg(ivy"com.lihaoyi::pprint:0.5.3")
      val Success(paths) = evalDeps(deps)
      assert(paths.nonEmpty)
    }

    "resolveValidDepsWithClassifier" - {
      val deps = Agg(ivy"org.lwjgl:lwjgl:3.1.1;classifier=natives-macos")
      val Success(paths) = evalDeps(deps)
      assert(paths.nonEmpty)
      assert(paths.items.next.path.toString.contains("natives-macos"))
    }

    "resolveTransitiveRuntimeDeps" - {
      val deps = Agg(ivy"org.mockito:mockito-core:2.7.22")
      val Success(paths) = evalDeps(deps)
      assert(paths.nonEmpty)
      assert(paths.exists(_.path.toString.contains("objenesis")))
      assert(paths.exists(_.path.toString.contains("byte-buddy")))
    }

    "excludeTransitiveDeps" - {
      val deps = Agg(ivy"com.lihaoyi::pprint:0.5.3".exclude("com.lihaoyi" -> "fansi_2.12"))
      val Success(paths) = evalDeps(deps)
      assert(!paths.exists(_.path.toString.contains("fansi_2.12")))
    }

    "excludeTransitiveDepsByOrg" - {
      val deps = Agg(ivy"com.lihaoyi::pprint:0.5.3".excludeOrg("com.lihaoyi"))
      val Success(paths) = evalDeps(deps)
      assert(!paths.exists(path =>
        path.path.toString.contains("com/lihaoyi") && !path.path.toString.contains("pprint_2.12")
      ))
    }

    "excludeTransitiveDepsByName" - {
      val deps = Agg(ivy"com.lihaoyi::pprint:0.5.3".excludeName("fansi_2.12"))
      val Success(paths) = evalDeps(deps)
      assert(!paths.exists(_.path.toString.contains("fansi_2.12")))
    }

    "errOnInvalidOrgDeps" - {
      val deps = Agg(ivy"xxx.yyy.invalid::pprint:0.5.3")
      val Failure(errMsg, _) = evalDeps(deps)
      assert(errMsg.contains("xxx.yyy.invalid"))
    }

    "errOnInvalidVersionDeps" - {
      val deps = Agg(ivy"com.lihaoyi::pprint:invalid.version.num")
      val Failure(errMsg, _) = evalDeps(deps)
      assert(errMsg.contains("invalid.version.num"))
    }

    "errOnPartialSuccess" - {
      val deps = Agg(ivy"com.lihaoyi::pprint:0.5.3", ivy"fake::fake:fake")
      val Failure(errMsg, _) = evalDeps(deps)
      assert(errMsg.contains("fake"))
    }
  }
}
