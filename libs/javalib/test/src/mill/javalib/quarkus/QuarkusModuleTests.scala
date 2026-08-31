package mill.javalib.quarkus

import mill.api.Discover
import mill.javalib.{DepSyntax, MavenModule}
import mill.testkit.{TestRootModule, UnitTester}
import mill.util.TokenReaders.*
import utest.*

object QuarkusModuleTests extends TestSuite {

  object TestCase extends TestRootModule {
    object grpc extends QuarkusModule, MavenModule {
      def quarkusPlatformVersion = "3.31.3"

      def artifactGroupId = "com.lihaoyi.test"
      def artifactId = "quarkus-grpc-test"
      def artifactVersion = "0.0.1"

      def mvnDeps = Seq(
        mvn"io.quarkus:quarkus-grpc"
      )
    }

    lazy val millDiscover = Discover[this.type]
  }

  private val resourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "quarkus-grpc"

  val tests = Tests {
    test("dependencyCoordinates") {
      UnitTester(TestCase, resourcePath).scoped { eval =>
        val result = eval(TestCase.grpc.quarkusDependencies).runtimeChecked.fold(
          _.throwException,
          identity
        )
        val dependencies = result.value

        val platformClassifiers = Set(
          "linux-aarch_64",
          "linux-ppcle_64",
          "linux-s390_64",
          "linux-x86_32",
          "linux-x86_64",
          "osx-aarch_64",
          "osx-x86_64",
          "windows-x86_32",
          "windows-x86_64"
        )

        val protoc = dependencies.filter(d =>
          d.groupId == "com.google.protobuf" && d.artifactId == "protoc"
        )
        val grpcPlugin = dependencies.filter(d =>
          d.groupId == "io.grpc" && d.artifactId == "protoc-gen-grpc-java"
        )
        val quarkusPlugin = dependencies.find(d =>
          d.groupId == "io.quarkus" && d.artifactId == "quarkus-grpc-protoc-plugin"
        ).get
        val ordinaryJar = dependencies.find(d =>
          d.groupId == "io.quarkus" && d.artifactId == "quarkus-grpc-codegen"
        ).get

        assert(
          protoc.size == platformClassifiers.size,
          protoc.map(_.classifier).toSet == platformClassifiers,
          protoc.forall(_.artifactType == "exe"),
          grpcPlugin.size == platformClassifiers.size,
          grpcPlugin.map(_.classifier).toSet == platformClassifiers,
          grpcPlugin.forall(_.artifactType == "exe"),
          quarkusPlugin.artifactType == "jar",
          quarkusPlugin.classifier == "shaded",
          ordinaryJar.artifactType == "jar",
          ordinaryJar.classifier.isEmpty
        )
      }
    }

    test("grpcCodeGeneration") {
      UnitTester(TestCase, resourcePath).scoped { eval =>
        val result = eval(TestCase.grpc.quarkusCodeGen).runtimeChecked.fold(
          _.throwException,
          identity
        )
        val generatedFiles = os.walk(result.value.path).filter(os.isFile).map(_.last).toSet

        assert(
          generatedFiles.contains("Ping.java"),
          generatedFiles.contains("Pinger.java")
        )
      }
    }
  }
}
