package mill.scalalib

import mill.*
import mill.testkit.TestRootModule
import mill.util.TokenReaders._
import HelloWorldTests.*
import mill.api.Discover
trait ScalaAssemblyTestUtils {

  val akkaHttpDeps = Seq(mvn"com.typesafe.akka::akka-http:10.0.13")

  object HelloWorldAkkaHttpAppend extends TestRootModule {
    object core extends HelloWorldModuleWithMain {
      override def mvnDeps = akkaHttpDeps
      override def assemblyRules = Seq(Assembly.Rule.Append("reference.conf"))
    }

    lazy val millDiscover = Discover[this.type]
  }

  object HelloWorldAkkaHttpExclude extends TestRootModule {
    object core extends HelloWorldModuleWithMain {
      override def mvnDeps = akkaHttpDeps
      override def assemblyRules = Seq(Assembly.Rule.Exclude("reference.conf"))
    }

    lazy val millDiscover = Discover[this.type]

  }

  object HelloWorldAkkaHttpAppendPattern extends TestRootModule {
    object core extends HelloWorldModuleWithMain {
      override def mvnDeps = akkaHttpDeps
      override def assemblyRules = Seq(Assembly.Rule.AppendPattern(".*.conf"))
    }

    lazy val millDiscover = Discover[this.type]

  }

  object HelloWorldAkkaHttpExcludePattern extends TestRootModule {
    object core extends HelloWorldModuleWithMain {
      override def mvnDeps = akkaHttpDeps
      override def assemblyRules = Seq(Assembly.Rule.ExcludePattern(".*.conf"))
    }

    lazy val millDiscover = Discover[this.type]
  }

  object HelloWorldAkkaHttpRelocate extends TestRootModule {
    object core extends HelloWorldModuleWithMain {
      override def mvnDeps = akkaHttpDeps
      override def assemblyRules = Seq(Assembly.Rule.Relocate("akka.**", "shaded.akka.@1"))
    }

    lazy val millDiscover = Discover[this.type]
  }

  object HelloWorldAkkaHttpNoRules extends TestRootModule {
    object core extends HelloWorldModuleWithMain {
      override def mvnDeps = akkaHttpDeps
      override def assemblyRules = Seq.empty
    }

    lazy val millDiscover = Discover[this.type]
  }

  object HelloWorldMultiAppend extends TestRootModule {
    object core extends HelloWorldModuleWithMain {
      override def moduleDeps = Seq(model)
      override def assemblyRules = Seq(Assembly.Rule.Append("reference.conf"))
    }
    object model extends HelloWorldModule

    lazy val millDiscover = Discover[this.type]
  }

  object HelloWorldMultiExclude extends TestRootModule {
    object core extends HelloWorldModuleWithMain {
      override def moduleDeps = Seq(model)
      override def assemblyRules = Seq(Assembly.Rule.Exclude("reference.conf"))
    }
    object model extends HelloWorldModule

    lazy val millDiscover = Discover[this.type]
  }

  object HelloWorldMultiAppendPattern extends TestRootModule {
    object core extends HelloWorldModuleWithMain {
      override def moduleDeps = Seq(model)
      override def assemblyRules = Seq(Assembly.Rule.AppendPattern(".*.conf"))
    }
    object model extends HelloWorldModule

    lazy val millDiscover = Discover[this.type]
  }

  object HelloWorldMultiAppendByPatternWithSeparator extends TestRootModule {
    object core extends HelloWorldModuleWithMain {
      override def moduleDeps = Seq(model)
      override def assemblyRules = Seq(Assembly.Rule.AppendPattern(".*.conf", "\n"))
    }
    object model extends HelloWorldModule

    lazy val millDiscover = Discover[this.type]
  }

  object HelloWorldMultiExcludePattern extends TestRootModule {
    object core extends HelloWorldModuleWithMain {
      override def moduleDeps = Seq(model)
      override def assemblyRules = Seq(Assembly.Rule.ExcludePattern(".*.conf"))
    }
    object model extends HelloWorldModule

    lazy val millDiscover = Discover[this.type]
  }

  object HelloWorldMultiNoRules extends TestRootModule {
    object core extends HelloWorldModuleWithMain {
      override def moduleDeps = Seq(model)
      override def assemblyRules = Seq.empty
    }
    object model extends HelloWorldModule

    lazy val millDiscover = Discover[this.type]
  }

  val helloWorldMultiResourcePath =
    os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "hello-world-multi"

}
