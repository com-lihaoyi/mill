import mill._, javalib._, publish._
import $ivy.`com.lihaoyi::mill-contrib-jmh:$MILL_VERSION`
import contrib.jmh.JmhModule

object commonsio extends RootModule with PublishModule with MavenModule {
  def publishVersion = "2.17.0-SNAPSHOT"

  def pomSettings = PomSettings(
    description = artifactName(),
    organization = "org.apache.commons",
    url = "https://github.com/apache/commons-io",
    licenses = Seq(License.`Apache-2.0`),
    versionControl = VersionControl.github(owner = "apache", repo = "commons-io"),
    developers = Nil
  )

  def ivyDeps = Agg(
    ivy"com.google.auto.service:auto-service:1.0",
  )

  def javacOptions = Seq("-processor", "com.google.auto.service.processor.AutoServiceProcessor")


  object test extends MavenModuleTests with TestModule.Junit5 with  JmhModule{
    def jmhCoreVersion = "1.37"
    def ivyDeps = super.ivyDeps() ++ Agg(
      ivy"org.junit.jupiter:junit-jupiter:5.10.3",
      ivy"org.junit-pioneer:junit-pioneer:1.9.1",
      ivy"net.bytebuddy:byte-buddy:1.14.18",
      ivy"net.bytebuddy:byte-buddy-agent:1.14.18",
      ivy"org.mockito:mockito-inline:4.11.0",
      ivy"com.google.jimfs:jimfs:1.3.0",
      ivy"org.apache.commons:commons-lang3:3.14.0",
      ivy"commons-codec:commons-codec:1.17.1",
      ivy"org.openjdk.jmh:jmh-core:1.37",
      ivy"org.openjdk.jmh:jmh-generator-annprocess:1.37",
    )
  }
}

// JimFS is a small Java library
//
// Project home: https://github.com/apache/commons-io

/** Usage

> ./mill compile
compiling 254 Java sources...
...

> ./mill test.compile
compiling 261 Java sources...
...

> ./mill test
Test org.apache.commons.io.FileUtilsTest#testCopyFile1() started
Test org.apache.commons.io.FileUtilsTest#testCopyFile1() finished, took ...
Test org.apache.commons.io.FileSystemTest#testIsLegalName() started
Test org.apache.commons.io.FileSystemTest#testIsLegalName() finished, took ...
Test org.apache.commons.io.filefilter.AndFileFilterTest#testAdd() started
Test org.apache.commons.io.filefilter.AndFileFilterTest#testAdd() finished, took ...
...

> ./mill test.runJmh '.*PathUtilsContentEqualsBenchmark' -bm SingleShotTime
Benchmark                                                                Mode  Cnt ...
PathUtilsContentEqualsBenchmark.testCurrent_fileContentEquals              ss    5 ...
PathUtilsContentEqualsBenchmark.testCurrent_fileContentEquals_Blackhole    ss    5 ...
PathUtilsContentEqualsBenchmark.testProposal_contentEquals                 ss    5 ...
PathUtilsContentEqualsBenchmark.testProposal_contentEquals_Blackhole       ss    5 ...

*/