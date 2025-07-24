package mill.scalalib.publish

import utest.*

object PublishInfoTests extends TestSuite {
  override def tests: Tests = Tests {
    test("IvyMetadata") {
      test("parseFromFile") {
        test("testProject") {
          def parse(name: String) = PublishInfo.IvyMetadata.parseFromFile(
            fileName = name,
            artifactId = "testProject_3.3.4",
            artifactVersion = "0.0.1-SNAPSHOT"
          )

          test("pom") {
            val actual = parse("testProject_3.3.4-0.0.1-SNAPSHOT.pom")
            assert(actual == PublishInfo.IvyMetadata.Pom)
          }

          test("jar") {
            val actual = parse("testProject_3.3.4-0.0.1-SNAPSHOT.jar")
            assert(actual == PublishInfo.IvyMetadata.Jar)
          }

          test("sources") {
            val actual = parse("testProject_3.3.4-0.0.1-SNAPSHOT-sources.jar")
            assert(actual == PublishInfo.IvyMetadata.SourcesJar)
          }

          test("docs") {
            val actual = parse("testProject_3.3.4-0.0.1-SNAPSHOT-javadoc.jar")
            assert(actual == PublishInfo.IvyMetadata.DocJar)
          }
        }

        test("chisel-plugin") {
          def parse(name: String) = PublishInfo.IvyMetadata.parseFromFile(
            fileName = name,
            artifactId = "chisel-plugin_3.3.4",
            artifactVersion = "7.0.0-RC3+5-c4ca27e5-SNAPSHOT"
          )

          test("pom") {
            val actual = parse("chisel-plugin_3.3.4-7.0.0-RC3+5-c4ca27e5-SNAPSHOT.pom")
            assert(actual == PublishInfo.IvyMetadata.Pom)
          }

          test("jar") {
            val actual = parse("chisel-plugin_3.3.4-7.0.0-RC3+5-c4ca27e5-SNAPSHOT.jar")
            assert(actual == PublishInfo.IvyMetadata.Jar)
          }

          test("sources") {
            val actual = parse("chisel-plugin_3.3.4-7.0.0-RC3+5-c4ca27e5-SNAPSHOT-sources.jar")
            assert(actual == PublishInfo.IvyMetadata.SourcesJar)
          }

          test("docs") {
            val actual = parse("chisel-plugin_3.3.4-7.0.0-RC3+5-c4ca27e5-SNAPSHOT-javadoc.jar")
            assert(actual == PublishInfo.IvyMetadata.DocJar)
          }
        }
      }
    }
  }
}
