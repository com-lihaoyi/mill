package mill.scalalib

import mill.*
import mill.testkit.{TestBaseModule, UnitTester}
import utest.*
import mill.main.TokenReaders._
import java.util.jar.JarFile
import scala.util.Using
import HelloWorldTests.*
import mill.define.Discover
trait ScalaAssemblyTestUtils {

  val akkaHttpDeps = Agg(ivy"com.typesafe.akka::akka-http:10.0.13")

  object HelloWorldAkkaHttpAppend extends TestBaseModule {
    object core extends HelloWorldModuleWithMain {
      override def ivyDeps = akkaHttpDeps
      override def assemblyRules = Seq(Assembly.Rule.Append("reference.conf"))
    }

    def millDiscover = Discover[this.type]
  }

  object HelloWorldAkkaHttpExclude extends TestBaseModule {
    object core extends HelloWorldModuleWithMain {
      override def ivyDeps = akkaHttpDeps
      override def assemblyRules = Seq(Assembly.Rule.Exclude("reference.conf"))
    }

    def millDiscover = Discover[this.type]

  }

  object HelloWorldAkkaHttpAppendPattern extends TestBaseModule {
    object core extends HelloWorldModuleWithMain {
      override def ivyDeps = akkaHttpDeps
      override def assemblyRules = Seq(Assembly.Rule.AppendPattern(".*.conf"))
    }

    def millDiscover = Discover[this.type]

  }

  object HelloWorldAkkaHttpExcludePattern extends TestBaseModule {
    object core extends HelloWorldModuleWithMain {
      override def ivyDeps = akkaHttpDeps
      override def assemblyRules = Seq(Assembly.Rule.ExcludePattern(".*.conf"))
    }

    def millDiscover = Discover[this.type]
  }

  object HelloWorldAkkaHttpRelocate extends TestBaseModule {
    object core extends HelloWorldModuleWithMain {
      override def ivyDeps = akkaHttpDeps
      override def assemblyRules = Seq(Assembly.Rule.Relocate("akka.**", "shaded.akka.@1"))
    }

    def millDiscover = Discover[this.type]
  }

  object HelloWorldAkkaHttpNoRules extends TestBaseModule {
    object core extends HelloWorldModuleWithMain {
      override def ivyDeps = akkaHttpDeps
      override def assemblyRules = Seq.empty
    }

    def millDiscover = Discover[this.type]
  }

  object HelloWorldMultiAppend extends TestBaseModule {
    object core extends HelloWorldModuleWithMain {
      override def moduleDeps = Seq(model)
      override def assemblyRules = Seq(Assembly.Rule.Append("reference.conf"))
    }
    object model extends HelloWorldModule

    def millDiscover = Discover[this.type]
  }

  object HelloWorldMultiExclude extends TestBaseModule {
    object core extends HelloWorldModuleWithMain {
      override def moduleDeps = Seq(model)
      override def assemblyRules = Seq(Assembly.Rule.Exclude("reference.conf"))
    }
    object model extends HelloWorldModule

    def millDiscover = Discover[this.type]
  }

  object HelloWorldMultiAppendPattern extends TestBaseModule {
    object core extends HelloWorldModuleWithMain {
      override def moduleDeps = Seq(model)
      override def assemblyRules = Seq(Assembly.Rule.AppendPattern(".*.conf"))
    }
    object model extends HelloWorldModule

    def millDiscover = Discover[this.type]
  }

  object HelloWorldMultiAppendByPatternWithSeparator extends TestBaseModule {
    object core extends HelloWorldModuleWithMain {
      override def moduleDeps = Seq(model)
      override def assemblyRules = Seq(Assembly.Rule.AppendPattern(".*.conf", "\n"))
    }
    object model extends HelloWorldModule

    def millDiscover = Discover[this.type]
  }

  object HelloWorldMultiExcludePattern extends TestBaseModule {
    object core extends HelloWorldModuleWithMain {
      override def moduleDeps = Seq(model)
      override def assemblyRules = Seq(Assembly.Rule.ExcludePattern(".*.conf"))
    }
    object model extends HelloWorldModule

    def millDiscover = Discover[this.type]
  }

  object HelloWorldMultiNoRules extends TestBaseModule {
    object core extends HelloWorldModuleWithMain {
      override def moduleDeps = Seq(model)
      override def assemblyRules = Seq.empty
    }
    object model extends HelloWorldModule

    def millDiscover = Discover[this.type]
  }

  val helloWorldMultiResourcePath =
    os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "hello-world-multi"

}
