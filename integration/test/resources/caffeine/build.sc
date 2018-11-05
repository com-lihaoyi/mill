
import mill._
import mill.scalalib._
import coursier.MavenRepository
import mill.modules.Jvm
import $file.deps
import deps.{benchmarkLibraries, benchmarkVersions, libraries, testLibraries, testVersions, versions}

trait CaffeineModule extends MavenModule{
  def repositories = super.repositories ++ Seq(
    coursier.ivy.IvyRepository.parse(
      "https://dl.bintray.com/sbt/sbt-plugin-releases/" +
      coursier.ivy.Pattern.default.string,
      dropInfoAttributes = true
    ).toOption.get,
    MavenRepository("https://jcenter.bintray.com/"),
    MavenRepository("https://jitpack.io/"),
    MavenRepository("http://repo.spring.io/plugins-release")
  )
  trait Tests extends super.Tests{
    def testFrameworks = Seq("com.novocode.junit.JUnitFramework")
    def ivyDeps = Agg(
      ivy"com.novocode:junit-interface:0.11",
      ivy"com.lihaoyi:mill-contrib-testng:${sys.props("MILL_VERSION")}",
      libraries.guava,
      testLibraries.mockito,
      testLibraries.hamcrest,
      testLibraries.awaitility,
    ) ++
      testLibraries.testng ++
      testLibraries.osgiRuntime ++
      testLibraries.osgiCompile
  }
}
object caffeine extends CaffeineModule {

  def ivyDeps = Agg(
    libraries.jsr305,
  )

  def generatedSources = T{
    val out = T.ctx().dest
    val mains = Seq(
      "com.github.benmanes.caffeine.cache.NodeFactoryGenerator",
      "com.github.benmanes.caffeine.cache.LocalCacheFactoryGenerator",
    )
    for(mainCls <- mains) Jvm.runSubprocess(
      mainCls,
      javaPoet.runClasspath().map(_.path),
      javaPoet.forkArgs(),
      javaPoet.forkEnv(),
      Seq(out.toString),
      workingDir = ammonite.ops.pwd
    )

    Seq(PathRef(out))
  }

  object javaPoet extends MavenModule{
    def millSourcePath = caffeine.millSourcePath
    def sources = T.sources(
      millSourcePath / 'src / 'javaPoet / 'java
    )
    def resources = T.sources(
      millSourcePath / 'src / 'javaPoet / 'resources
    )
    def ivyDeps = Agg(
      libraries.guava,
      libraries.jsr305,
      libraries.javapoet,
      libraries.commonsLang3
    )
  }

  object test extends Tests{
    def testFrameworks = Seq("mill.testng.TestNGFramework")
    def ivyDeps = super.ivyDeps() ++ Agg(
      libraries.ycsb,
      libraries.fastutil,
      libraries.guava,
      libraries.commonsLang3,
      testLibraries.junit,
      testLibraries.jctools,
      testLibraries.guavaTestLib,
    ) ++
      testLibraries.testng

    def allSourceFiles = super.allSourceFiles().filter(_.path.last != "OSGiTest.java")
  }
}

object guava extends CaffeineModule {
  def moduleDeps = Seq(caffeine)
  def ivyDeps = Agg(libraries.guava)
  object test extends Tests{
    def ivyDeps = super.ivyDeps() ++ Agg(
      testLibraries.junit,
      testLibraries.truth,
      testLibraries.jctools,
      testLibraries.easymock,
      testLibraries.guavaTestLib
    )
    def allSourceFiles = super.allSourceFiles().filter(_.path.last != "OSGiTest.java")
    def forkArgs = Seq(
      "-Dguava.osgi.version=" + versions.guava,
      "-Dcaffeine.osgi.jar=" + caffeine.jar().path,
      "-Dcaffeine-guava.osgi.jar=" + guava.jar().path
    )
  }
}

object jcache extends CaffeineModule {
  def moduleDeps = Seq(caffeine)
  def ivyDeps = Agg(libraries.jcache, libraries.config, libraries.jsr330)
  object test extends Tests{
    def ivyDeps = super.ivyDeps() ++ Agg(
      testLibraries.junit,
      testLibraries.jcacheTck,
      testLibraries.jcacheTckTests,
      testLibraries.jcacheGuice,
      testLibraries.guavaTestLib
    ) ++
      testLibraries.testng
  }
}

object simulator extends CaffeineModule {
  def moduleDeps = Seq(caffeine)
  def ivyDeps = Agg(
    libraries.xz,
    libraries.akka,
    libraries.ycsb,
    libraries.guava,
    libraries.fastutil,
    libraries.flipTables,
    benchmarkLibraries.ohc,
    libraries.commonsLang3,
    libraries.commonsCompress,
    benchmarkLibraries.tcache,
    libraries.univocityParsers,
    benchmarkLibraries.cache2k,
    benchmarkLibraries.ehcache3,
    benchmarkLibraries.rapidoid,
    benchmarkLibraries.collision,
    benchmarkLibraries.slf4jNop,
    benchmarkLibraries.expiringMap,
    benchmarkLibraries.elasticSearch
  )
  object test extends Tests{

    def ivyDeps = super.ivyDeps() ++ testLibraries.testng
  }
}
