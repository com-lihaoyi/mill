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

  override def allSources = T {
    //TODO: This should be in TwirlModule
    //TODO: We actually want super.allSources() ++ twirlSources but it breaks
    Seq(javaSources(), sources()) ++ twirlSources(basePath / 'src / 'main / 'twirl)
  }

  object SbtDeps extends SbtDeps {
    def deps = Seq(
      "org.eclipse.jgit"                %  "org.eclipse.jgit.http.server" % "4.9.0.201710071750-r",
      "org.eclipse.jgit"                %  "org.eclipse.jgit.archive"     % "4.9.0.201710071750-r",
      "org.scalatra"                    %% "scalatra"                     % ScalatraVersion,
      "org.scalatra"                    %% "scalatra-json"                % ScalatraVersion,
      "org.scalatra"                    %% "scalatra-forms"               % ScalatraVersion,
      "org.json4s"                      %% "json4s-jackson"               % "3.5.1",
      "commons-io"                      %  "commons-io"                   % "2.5",
      "io.github.gitbucket"             %  "solidbase"                    % "1.0.2",
      "io.github.gitbucket"             %  "markedj"                      % "1.0.15",
      "org.apache.commons"              %  "commons-compress"             % "1.13",
      "org.apache.commons"              %  "commons-email"                % "1.4",
      "org.apache.httpcomponents"       %  "httpclient"                   % "4.5.3",
      "org.apache.sshd"                 %  "apache-sshd"                  % "1.4.0" exclude("org.slf4j","slf4j-jdk14"),
      "org.apache.tika"                 %  "tika-core"                    % "1.14",
      "com.github.takezoe"              %% "blocking-slick-32"            % "0.0.10",
      "com.novell.ldap"                 %  "jldap"                        % "2009-10-07",
      "com.h2database"                  %  "h2"                           % "1.4.195",
      "org.mariadb.jdbc"                %  "mariadb-java-client"          % "2.1.2",
      "org.postgresql"                  %  "postgresql"                   % "42.0.0",
      "ch.qos.logback"                  %  "logback-classic"              % "1.2.3",
      "com.zaxxer"                      %  "HikariCP"                     % "2.6.1",
      "com.typesafe"                    %  "config"                       % "1.3.1",
      "com.typesafe.akka"               %% "akka-actor"                   % "2.5.0",
      "fr.brouillard.oss.security.xhub" %  "xhub4j-core"                  % "1.0.0",
      "com.github.bkromhout"            %  "java-diff-utils"              % "2.1.1",
      "org.cache2k"                     %  "cache2k-all"                  % "1.0.0.CR1",
      "com.enragedginger"               %% "akka-quartz-scheduler"        % "1.6.0-akka-2.4.x" exclude("c3p0","c3p0"),
      "net.coobird"                     %  "thumbnailator"                % "0.4.8",
      "com.github.zafarkhaja"           %  "java-semver"                  % "0.9.0",
      "org.eclipse.jetty"               %  "jetty-webapp"                 % JettyVersion     % "provided",
      "javax.servlet"                   %  "javax.servlet-api"            % "3.1.0"          % "provided",
      "junit"                           %  "junit"                        % "4.12"           % "test",
      "org.scalatra"                    %% "scalatra-scalatest"           % ScalatraVersion  % "test",
      "org.mockito"                     %  "mockito-core"                 % "2.7.22"         % "test",
      "com.wix"                         %  "wix-embedded-mysql"           % "2.1.4"          % "test",
      "ru.yandex.qatools.embed"         %  "postgresql-embedded"          % "2.0"            % "test",
      "net.i2p.crypto"                  % "eddsa"                         % "0.1.0"
    )
  }

  override def repositories =
    super.repositories :+ MavenRepository("https://dl.bintray.com/bkromhout/maven")

  override def ivyDeps = SbtDeps.toMill ++ Seq(
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

