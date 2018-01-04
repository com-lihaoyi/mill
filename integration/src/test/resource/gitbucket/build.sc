import ammonite.ops.{Path, ls}
import coursier.MavenRepository
import mill.{PathRef, T}
import mill.scalalib.{Dep, Module, SbtModule}
import mill.util.Ctx



trait GitbucketModule extends SbtModule with TwirlModule {
  def scalaVersion = "2.12.4"
  def basePath =  ammonite.ops.pwd / 'target / 'workspace / 'gitbucket

  val ScalatraVersion = "2.6.1"
  val JettyVersion = "9.4.7.v20170914"

  override def repositories =
    super.repositories :+ MavenRepository("https://dl.bintray.com/bkromhout/maven")

  object test extends this.Tests{
    def testFramework = "org.scalatest.tools.Framework"

    override def forkArgs = super.forkArgs() ++ Seq("-Dgitbucket.home=target/gitbucket_home_for_test")
  }

  override def ivyDeps = Seq(
    Dep.Java("org.eclipse.jgit", "org.eclipse.jgit.http.server", "4.9.0.201710071750-r"),
    Dep.Java("org.eclipse.jgit", "org.eclipse.jgit.archive", "4.9.0.201710071750-r"),
    Dep.Scala("org.scalatra", "scalatra", "2.6.1"),
    Dep.Scala("org.scalatra", "scalatra-json", "2.6.1"),
    Dep.Scala("org.scalatra", "scalatra-forms", "2.6.1"),
    Dep.Scala("org.json4s", "json4s-jackson", "3.5.1"),
    Dep.Java("commons-io", "commons-io", "2.5"),
    Dep.Java("io.github.gitbucket", "solidbase", "1.0.2"),
    Dep.Java("io.github.gitbucket", "markedj", "1.0.15"),
    Dep.Java("org.apache.commons", "commons-compress", "1.13"),
    Dep.Java("org.apache.commons", "commons-email", "1.4"),
    Dep.Java("org.apache.httpcomponents", "httpclient", "4.5.3"),
    Dep.Java("org.apache.sshd", "apache-sshd", "1.4.0"),
    Dep.Java("org.apache.tika", "tika-core", "1.14"),
    Dep.Scala("com.github.takezoe", "blocking-slick-32", "0.0.10"),
    Dep.Java("com.novell.ldap", "jldap", "2009-10-07"),
    Dep.Java("com.h2database", "h2", "1.4.195"),
    Dep.Java("org.mariadb.jdbc", "mariadb-java-client", "2.1.2"),
    Dep.Java("org.postgresql", "postgresql", "42.0.0"),
    Dep.Java("ch.qos.logback", "logback-classic", "1.2.3"),
    Dep.Java("com.zaxxer", "HikariCP", "2.6.1"),
    Dep.Java("com.typesafe", "config", "1.3.1"),
    Dep.Scala("com.typesafe.akka", "akka-actor", "2.5.0"),
    Dep.Java("fr.brouillard.oss.security.xhub", "xhub4j-core", "1.0.0"),
    Dep.Java("com.github.bkromhout", "java-diff-utils", "2.1.1"),
    Dep.Java("org.cache2k", "cache2k-all", "1.0.0.CR1"),
    Dep.Scala("com.enragedginger", "akka-quartz-scheduler", "1.6.0-akka-2.4.x"),
    Dep.Java("net.coobird", "thumbnailator", "0.4.8"),
    Dep.Java("com.github.zafarkhaja", "java-semver", "0.9.0"),
    Dep.Java("org.eclipse.jetty", "jetty-webapp", "9.4.7.v20170914"),
    Dep.Java("javax.servlet", "javax.servlet-api", "3.1.0"),
    Dep.Java("junit", "junit", "4.12"),
    Dep.Scala("org.scalatra", "scalatra-scalatest", "2.6.1"),
    Dep.Java("org.mockito", "mockito-core", "2.7.22"),
    Dep.Java("com.wix", "wix-embedded-mysql", "2.1.4"),
    Dep.Java("ru.yandex.qatools.embed", "postgresql-embedded", "2.0"),
    Dep.Java("net.i2p.crypto", "eddsa", "0.1.0"),
    Dep.Scala("com.typesafe.play",  "twirl-api", "1.3.13")
  )
}
import $ivy.`com.typesafe.play::twirl-compiler:1.3.13`, play.twirl.compiler.TwirlCompiler

object gitbucket extends GitbucketModule

trait TwirlModule extends Module {
  override def allSources = T {
    super.allSources() ++ TwirlModule.twirlSources(basePath / 'src / 'main / 'twirl)
  }
}

object TwirlModule {
  def twirlSources(inputDir: Path)(implicit ctx: Ctx): Seq[PathRef] = {
    val outputDir = ctx.dest / 'twirl

    val twirlFiles = ls.rec(inputDir).filter(_.name.contains(".scala."))

    val htmlFiles =  twirlFiles.filter(_.name.endsWith(".scala.html"))
    val xmlFiles =  twirlFiles.filter(_.name.endsWith(".scala.xml"))

    val handledHtml = htmlFiles.map { tFile =>
      TwirlCompiler.compile(tFile.toIO, inputDir.toIO, outputDir.toIO, "play.twirl.api.HtmlFormat", additionalImports = TwirlCompiler.DefaultImports)
    }

    val handledXml = xmlFiles.map { tFile =>
      TwirlCompiler.compile(tFile.toIO, inputDir.toIO, outputDir.toIO, "play.twirl.api.XmlFormat", additionalImports = TwirlCompiler.DefaultImports)
    }

    Seq(PathRef(outputDir))
  }
}
