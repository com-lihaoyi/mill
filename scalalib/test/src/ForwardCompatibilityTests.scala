package mill
package scalalib

import utest._
import utest.framework.TestPath
import mill.util.{TestEvaluator, TestUtil}
import mill.scalalib.publish._
import coursier.{Repository, LocalRepositories}

object ForwardCompatibilityTests extends TestSuite {
  trait ForwardCompat extends TestUtil.BaseModule {
    def fooArtifactName: String = ""
    def barModuleDeps: Seq[JavaModule] = Seq.empty
    def barIvyDeps: T[Agg[Dep]] = Agg.empty[Dep]
    def barExtraRepositories: Seq[Repository] = Seq.empty

    object foo extends ScalaModule with PublishModule {
      override def scalaVersion = "3.1.3-RC1-bin-20220303-727395c-NIGHTLY" // TODO: use stable version
      override def scalaOutputVersion = "3.0.2"
      override def publishVersion = "0.0.1"
      override def artifactName = fooArtifactName
      override def pomSettings = PomSettings(
        description = "My first library",
        organization = "com.lihaoyi",
        url = "https://example.org",
        licenses = Seq(License.MIT),
        versionControl = VersionControl(),
        developers = Seq()
      )
      
      object test extends Tests with TestModule.Utest {
        override def ivyDeps = Agg(ivy"com.lihaoyi::utest::0.7.11")
      }
    }

    object bar extends ScalaModule {
      override def scalaVersion = "3.0.2"
      override def ivyDeps = barIvyDeps
      override def moduleDeps = barModuleDeps
      override def repositoriesTask = T.task { super.repositoriesTask() ++ barExtraRepositories }
    }
  }

  object ForwardCompat1 extends ForwardCompat {
    def barModuleDeps: Seq[JavaModule] = Seq(this.foo)
  }

  object ForwardCompat2 extends ForwardCompat {
    def fooArtifactName = "scala-forward-compat-test-ivy"
    def barIvyDeps = Agg(ivy"com.lihaoyi::${fooArtifactName}:${this.foo.publishVersion()}")
  }

  object ForwardCompat3 extends ForwardCompat {
    def fooArtifactName = "scala-forward-compat-test-maven"
    def barIvyDeps = Agg(ivy"com.lihaoyi::${fooArtifactName}:${this.foo.publishVersion()}")
    def barExtraRepositories = Seq(LocalRepositories.Dangerous.maven2Local)
  }

  val resourcePath = os.pwd / "scalalib" / "test" / "resources" / "forward-compatibility"

  def workspaceTest[T](
      m: TestUtil.BaseModule,
      resourcePath: os.Path = resourcePath
  )(t: TestEvaluator => T)(
      implicit tp: TestPath
  ): T = {
    val eval = new TestEvaluator(m)
    os.remove.all(m.millSourcePath)
    os.remove.all(eval.outPath)
    os.makeDir.all(m.millSourcePath / os.up)
    os.copy(resourcePath, m.millSourcePath)
    t(eval)
  }

  def tests: Tests = Tests {
    "run compatible" - workspaceTest(ForwardCompat1) { eval => 
      val Right(_) = eval.apply(ForwardCompat1.foo.run())
    }
    "test compatible" - workspaceTest(ForwardCompat1) { eval => 
      val Right(_) = eval.apply(ForwardCompat1.foo.test.test())
    }
    "run dependent module" - workspaceTest(ForwardCompat1) { eval => 
      val Right(_) = eval.apply(ForwardCompat1.bar.run())
    }
    "run ivy-dependent project" - workspaceTest(ForwardCompat2) { eval => 
      val Right((x, y)) = eval.apply(ForwardCompat2.foo.publishLocal())
      val Right(_) = eval.apply(ForwardCompat2.bar.run())
    }
    "run maven-dependent project" - workspaceTest(ForwardCompat3) { eval => 
      val Right(_) = eval.apply(ForwardCompat3.foo.publishM2Local())
      val Right(_) = eval.apply(ForwardCompat3.bar.run())
    }
  }
}
