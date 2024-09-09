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
    deps.map(Lib.depToBoundDep(_, scala212Version, ""))
  )

  def assertRoundTrip(deps: Agg[Dep], simplified: Boolean) = {
    for (dep <- deps) {
      val unparsed = Dep.unparse(dep)
      if (simplified) {
        assert(unparsed.nonEmpty)
        assert(Dep.parse(unparsed.get) == dep)
      } else {
        assert(unparsed.isEmpty)
      }
      assert(upickle.default.read[Dep](upickle.default.write(dep)) == dep)
    }
  }
  val tests = Tests {
    test("resolveValidDeps") {
      val deps = Agg(ivy"com.lihaoyi::pprint:0.5.3")
      val Success(paths) = evalDeps(deps)
      assert(paths.nonEmpty)
    }

    test("resolveValidDepsWithClassifier") {
      val deps = Agg(ivy"org.lwjgl:lwjgl:3.1.1;classifier=natives-macos")
      assertRoundTrip(deps, simplified = true)
      val Success(paths) = evalDeps(deps)
      assert(paths.nonEmpty)
      assert(paths.items.next().path.toString.contains("natives-macos"))
    }

    test("resolveTransitiveRuntimeDeps") {
      val deps = Agg(ivy"org.mockito:mockito-core:2.7.22")
      assertRoundTrip(deps, simplified = true)
      val Success(paths) = evalDeps(deps)
      assert(paths.nonEmpty)
      assert(paths.exists(_.path.toString.contains("objenesis")))
      assert(paths.exists(_.path.toString.contains("byte-buddy")))
    }

    test("excludeTransitiveDeps") {
      val deps = Agg(ivy"com.lihaoyi::pprint:0.5.3".exclude("com.lihaoyi" -> "fansi_2.12"))
      assertRoundTrip(deps, simplified = false)
      val Success(paths) = evalDeps(deps)
      assert(!paths.exists(_.path.toString.contains("fansi_2.12")))
    }

    test("excludeTransitiveDepsByOrg") {
      val deps = Agg(ivy"com.lihaoyi::pprint:0.5.3".excludeOrg("com.lihaoyi"))
      assertRoundTrip(deps, simplified = false)
      val Success(paths) = evalDeps(deps)
      assert(!paths.exists(path =>
        path.path.toString.contains("com/lihaoyi") && !path.path.toString.contains("pprint_2.12")
      ))
    }

    test("excludeTransitiveDepsByName") {
      val deps = Agg(ivy"com.lihaoyi::pprint:0.5.3".excludeName("fansi_2.12"))
      assertRoundTrip(deps, simplified = false)
      val Success(paths) = evalDeps(deps)
      assert(!paths.exists(_.path.toString.contains("fansi_2.12")))
    }

    test("errOnInvalidOrgDeps") {
      val deps = Agg(ivy"xxx.yyy.invalid::pprint:0.5.3")
      assertRoundTrip(deps, simplified = true)
      val Failure(errMsg, _) = evalDeps(deps)
      assert(errMsg.contains("xxx.yyy.invalid"))
    }

    test("errOnInvalidVersionDeps") {
      val deps = Agg(ivy"com.lihaoyi::pprint:invalid.version.num")
      assertRoundTrip(deps, simplified = true)
      val Failure(errMsg, _) = evalDeps(deps)
      assert(errMsg.contains("invalid.version.num"))
    }

    test("errOnPartialSuccess") {
      val deps = Agg(ivy"com.lihaoyi::pprint:0.5.3", ivy"fake::fake:fake")
      assertRoundTrip(deps, simplified = true)
      val Failure(errMsg, _) = evalDeps(deps)
      assert(errMsg.contains("fake"))
    }
  }
}
