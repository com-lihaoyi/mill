package mill.scalaplugin

import ammonite.ops._
import coursier.MavenRepository
import mill._
import mill.define.{Target, Task}
import mill.discover.Discovered
import mill.discover.Mirror.LabelledTarget

import mill.twirlplugin.TwirlModule

import utest._

trait GitbucketModule extends SbtScalaModule with TwirlModule {
  def scalaVersion = "2.12.4"
  def basePath = GitbucketTests.workspacePath

  val ScalatraVersion = "2.6.1"
  val JettyVersion = "9.4.7.v20170914"



  override def repositories =
    super.repositories :+ MavenRepository("https://dl.bintray.com/bkromhout/maven")

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

object Gitbucket extends GitbucketModule


object GitbucketTests extends TestSuite {
  val workspacePath = pwd / 'target / 'workspace / "gitbucket"
  val outputPath = workspacePath / 'out

  def eval[T](t: Task[T], mapping: Map[Target[_], LabelledTarget[_]]) =
    TestEvaluator.eval(mapping, outputPath)(t)

  val gitbucketMapping = Discovered.mapping(Gitbucket)

  def tests: Tests = Tests {
    prepareWorkspace()
    'compile - {
        val  Right((result, evalCount)) = eval(Gitbucket.compile, gitbucketMapping)
    }
  }

  def prepareWorkspace(): Unit = {
    import mill.util.Git
    rm(workspacePath)
    mkdir(workspacePath / up)
    Git.gitClone("https://github.com/gitbucket/gitbucket.git", "3f8069638c298cdab5637333ace9619a9442edfe", workspacePath)
  }
}

