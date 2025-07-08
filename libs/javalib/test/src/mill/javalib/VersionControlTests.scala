package mill.javalib

import mill.javalib.publish.{VersionControl, VersionControlConnection}

import utest._

object VersionControlTests extends TestSuite {

  import VersionControl._
  import VersionControlConnection._

  val tests = Tests {
    test("github") {
      assert(
        github("lihaoyi", "mill") ==
          VersionControl(
            browsableRepository = Some("https://github.com/lihaoyi/mill"),
            connection = Some("scm:git:git://github.com/lihaoyi/mill.git"),
            developerConnection = Some("scm:git:ssh://git@github.com:lihaoyi/mill.git"),
            tag = None
          )
      )
    }
    test("git") {
      assert(
        gitGit("example.org", "path.git", port = Some(9418)) ==
          "scm:git:git://example.org:9418/path.git"
      )

      assert(
        gitHttp("example.org") ==
          "scm:git:http://example.org/"
      )

      assert(
        gitHttps("example.org", "path.git") ==
          "scm:git:https://example.org/path.git"
      )

      assert(
        gitSsh("example.org", "path.git") ==
          "scm:git:ssh://example.org/path.git"
      )

      assert(
        gitFile("/home/gui/repos/foo/bare.git") ==
          "scm:git:file:///home/gui/repos/foo/bare.git"
      )

    }
    test("svn") {
      assert(
        svnSsh("example.org", "repo") ==
          "scm:svn:svn+ssh://example.org/repo"
      )
      assert(
        svnHttp("example.org", "repo", Some("user"), Some("pass")) ==
          "scm:svn:http://user:pass@example.org/repo"
      )
      assert(
        svnHttps("example.org", "repo", Some("user")) ==
          "scm:svn:https://user@example.org/repo"
      )
      assert(
        svnSvn("example.org", "repo", port = Some(3690)) ==
          "scm:svn:svn://example.org:3690/repo"
      )
      assert(
        svnFile("/var/svn/repo") ==
          "scm:svn:file:///var/svn/repo"
      )
    }
  }
}
