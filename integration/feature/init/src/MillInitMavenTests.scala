package mill.integration

import mill.testkit.{IntegrationTester, UtestIntegrationTestSuite}
import utest._

object MillInitMavenTests extends UtestIntegrationTestSuite {

  private def prep[T](githubSourceZipUrl: String)(f: IntegrationTester => T): T = {
    val zipFile = os.temp(requests.get(githubSourceZipUrl))
    val unzipDir = os.unzip(zipFile, os.temp.dir())
    val sourceDir = os.list(unzipDir).head

    integrationTest { tester =>
      for (p <- os.list(sourceDir)) os.move.into(p, tester.workspacePath)
      f(tester)
    }
  }

  def tests: Tests = Tests {

    // - Junit5
    // - maven-compiler-plugin release option
    test("jansi") {
      prep("https://github.com/fusesource/jansi/archive/refs/tags/jansi-2.4.1.zip") { tester =>
        import tester._

        val initRes = eval(("init", "--deps-object", "Deps"))
        initRes.out.contains(
          "generated 1 Mill build file(s)"
        ) ==> true
        initRes.isSuccess ==> true

        val compileRes = eval("compile")
        compileRes.err.contains(
          "compiling 20 Java sources"
        ) ==> true
        compileRes.isSuccess ==> true

        val testRes = eval("test")
        testRes.out.contains(
          "Test run finished: 0 failed, 1 ignored, 90 total"
        ) ==> true
        testRes.isSuccess ==> true

        val publishLocalRes = eval("publishLocal")
        publishLocalRes.err.contains(
          "Publishing Artifact(org.fusesource.jansi,jansi,2.4.1)"
        ) ==> true
        publishLocalRes.isSuccess ==> true
      }
    }

    // - multi-module
    // - TestNg
    // - maven-compiler-plugin release option
    test("dotenv") {
      prep("https://github.com/shyiko/dotenv/archive/refs/tags/0.1.1.zip") { tester =>
        import tester._

        val initRes = eval("init")
        initRes.out.contains(
          "generated 3 Mill build file(s)"
        ) ==> true
        initRes.isSuccess ==> true

        val resolveRes = eval(("resolve", "_"))
        Seq(
          "dotenv",
          "dotenv-guice"
        ).forall(resolveRes.out.contains) ==> true

        // JavaModule.JavaTests is not picking compileIvyDeps from outer module
        val compileRes = eval("__.compile")
        compileRes.err.contains("package com.google.inject does not exist") ==> true
        compileRes.isSuccess ==> false

        // even if compile error is fixed, TestNg version is not supported
        // val testRes = eval("__.test")
        // testRes.isSuccess ==> false
      }
    }

    // - multi-module
    // - Junit5
    // - submodule e2e directory and artifact name differ
    test("fastexcel") {
      prep("https://github.com/dhatim/fastexcel/archive/refs/tags/0.18.4.zip") { tester =>
        import tester._

        val initRes = eval("init")
        initRes.out.contains(
          "generated 4 Mill build file(s)"
        ) ==> true
        initRes.isSuccess ==> true

        val resolveRes = eval(("resolve", "_"))
        Seq(
          "fastexcel-reader",
          "fastexcel-writer",
          "e2e"
        ).forall(resolveRes.out.contains) ==> true

        // not sure why this happens but pom.xml has a hack to handle JPMS
        val compileRes = eval("__.compile")
        Seq(
          "module not found: org.apache.commons.compress",
          "module not found: com.fasterxml.aalto",
          "module not found: opczip"
        ).forall(compileRes.err.contains) ==> true
        compileRes.isSuccess ==> false
      }
    }

    // - multi-module
    // - Junit5
    // - build-helper-maven-plugin
    // - maven-compiler-plugin compilerArgs options
    // - submodule directory and artifact names differ
    // - multi line description, property <argLine.common>
    // - property <jetty.alpnAgent.path> contains quotes
    test("netty") {
      prep("https://github.com/netty/netty/archive/refs/tags/netty-4.1.114.Final.zip") { tester =>
        import tester._

        val initRes = eval(("init", "--publish-properties"))
        // cannot resolve native dependencies defined by build extension os-maven-plugin
        initRes.out.contains(
          """[codec-http2] dropping classifier ${os.detected.classifier} for dependency io.netty:netty-tcnative:2.0.66.Final
            |[transport-native-epoll] dropping classifier ${os.detected.classifier} for dependency io.netty:netty-tcnative:2.0.66.Final
            |[transport-native-kqueue] dropping classifier ${os.detected.classifier} for dependency io.netty:netty-tcnative:2.0.66.Final
            |[handler] dropping classifier ${os.detected.classifier} for dependency io.netty:netty-tcnative:2.0.66.Final
            |[example] dropping classifier ${os.detected.classifier} for dependency io.netty:netty-tcnative:2.0.66.Final
            |[testsuite] dropping classifier ${os.detected.classifier} for dependency io.netty:netty-tcnative:2.0.66.Final
            |[testsuite-shading] dropping classifier ${os.detected.classifier} for dependency io.netty:netty-tcnative:2.0.66.Final
            |[transport-blockhound-tests] dropping classifier ${os.detected.classifier} for dependency io.netty:netty-tcnative:2.0.66.Final
            |[microbench] dropping classifier ${os.detected.classifier} for dependency io.netty:netty-tcnative:2.0.66.Final
            |""".stripMargin
        ) ==> true
        initRes.out.contains(
          "generated 47 Mill build file(s)"
        ) ==> true
        initRes.isSuccess ==> true

        val resolveRes = eval(("resolve", "_"))
        Seq(
          "all",
          "dev-tools",
          "common",
          "buffer",
          "codec",
          "codec-dns",
          "codec-haproxy",
          "codec-http",
          "codec-http2",
          "codec-memcache",
          "codec-mqtt",
          "codec-redis",
          "codec-smtp",
          "codec-socks",
          "codec-stomp",
          "codec-xml",
          "resolver",
          "resolver-dns",
          "resolver-dns-classes-macos",
          "resolver-dns-native-macos",
          "transport",
          "transport-native-unix-common-tests",
          "transport-native-unix-common",
          "transport-classes-epoll",
          "transport-native-epoll",
          "transport-classes-kqueue",
          "transport-native-kqueue",
          "transport-rxtx",
          "transport-sctp",
          "transport-udt",
          "handler",
          "handler-proxy",
          "handler-ssl-ocsp",
          "example",
          "testsuite",
          "testsuite-autobahn",
          "testsuite-http2",
          "testsuite-osgi",
          "testsuite-shading",
          "testsuite-native",
          "testsuite-native-image",
          "testsuite-native-image-client",
          "testsuite-native-image-client-runtime-init",
          "transport-blockhound-tests",
          "microbench",
          "bom"
        ).forall(resolveRes.out.contains) ==> true

        // additional sources defined by build-helper-maven-plugin
        val sourcesRes = eval(("show", "transport-native-epoll.sources"))
        Seq(
          s"$workspacePath/transport-native-epoll/src/main/java",
          s"$workspacePath/transport-native-epoll/src/main/c"
        ).forall(sourcesRes.out.contains) ==> true

        // compile succeeds for submodule
        val compileRes = eval("codec.compile")
        compileRes.err.contains(
          "compiling 155 Java sources"
        ) ==> true
        compileRes.isSuccess ==> true

        // test compile fails for submodule due to missing native dependency
        val testRes = eval("codec.test")
        testRes.err.contains(
          "package io.netty.nativeimage does not exist"
        ) ==> true
        testRes.isSuccess ==> false

        // publishLocal fails for submodule (due to several Javadoc errors)
        val publishLocalRes = eval("codec.publishLocal")
        publishLocalRes.isSuccess ==> false
      }
    }
  }
}
