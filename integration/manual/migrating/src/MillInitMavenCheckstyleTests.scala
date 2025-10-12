package mill.integration

import mill.testkit.GitRepoIntegrationTestSuite
import utest.*

object MillInitMavenCheckstyleTests extends GitRepoIntegrationTestSuite {
  def tests = Tests {
    test - integrationTestGitRepo(
      "https://github.com/checkstyle/checkstyle.git",
      "checkstyle-11.0.0",
      linkMillExecutable = true
    ) { tester =>
      import tester.*
      eval("init", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
      eval("__.showModuleDeps", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true

      s"""Requires support for code generation using antlrv4.
         |
         |[error] .../src/main/java/com/puppycrawl/tools/checkstyle/JavaParser.java:43:52: cannot find symbol
         |[error]   symbol:   class JavaLanguageLexer
         |[error]   location: package com.puppycrawl.tools.checkstyle.grammar.java
         |""".stripMargin
    }
  }
}
