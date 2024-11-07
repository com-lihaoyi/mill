package mill.integration

import mill.testkit.{IntegrationTester, UtestIntegrationTestSuite}
import utest._

trait MillInitMavenTests extends UtestIntegrationTestSuite {

  final protected def prep[T](githubSourceZipUrl: String)(f: IntegrationTester => T): T =
    integrationTest { tester =>
      val zipFile = os.temp(requests.get(githubSourceZipUrl))
      val unzipDir = os.unzip(zipFile, os.temp.dir())
      val sourceDir = os.list(unzipDir).head
      // move fails on Windows, so copy
      for (p <- os.list(sourceDir))
        os.copy.into(p, tester.workspacePath, replaceExisting = true, createFolders = true)
      f(tester)
    }
}

object MillInitMavenJansiTests extends MillInitMavenTests {

  def tests: Tests = Tests {

    // - Junit5
    // - maven-compiler-plugin release option
    test - prep("https://github.com/fusesource/jansi/archive/refs/tags/jansi-2.4.1.zip") { tester =>
      import tester._

      val initRes = eval(("init", "--deps-object", "Deps"))
      assert(
        initRes.out.contains("generated 1 Mill build file(s)"),
        initRes.isSuccess
      )

      val compileRes = eval("compile")
      assert(
        compileRes.err.contains("compiling 20 Java sources"),
        compileRes.isSuccess
      )

      val testRes = eval("test")
      assert(
        testRes.out.contains("Test run finished: 0 failed, 1 ignored, 90 total"),
        testRes.isSuccess
      )

      val publishLocalRes = eval("publishLocal")
      assert(
        publishLocalRes.err.contains("Publishing Artifact(org.fusesource.jansi,jansi,2.4.1)"),
        publishLocalRes.isSuccess
      )
    }
  }
}

object MillInitMavenDotEnvTests extends MillInitMavenTests {

  def tests: Tests = Tests {
    // - multi-module
    // - TestNg
    // - maven-compiler-plugin release option
    test - prep("https://github.com/shyiko/dotenv/archive/refs/tags/0.1.1.zip") { tester =>
      import tester._

      val initRes = eval("init")
      assert(
        initRes.out.contains("generated 3 Mill build file(s)"),
        initRes.isSuccess
      )

      val resolveRes = eval(("resolve", "_"))
      assert(
        resolveRes.out.contains("dotenv"),
        resolveRes.out.contains("dotenv-guice"),
        resolveRes.isSuccess
      )

      // JavaModule.JavaTests is not picking compileIvyDeps from outer module
      val compileRes = eval("__.compile")
      assert(
        compileRes.err.contains(
          "DotEnvModuleTest.java:3:25: package com.google.inject does not exist"
        ),
        !compileRes.isSuccess
      )

      // even if compile error is fixed, TestNg version is not supported
      // val testRes = eval("__.test")
      // assert(!testRes.isSuccess)
    }
  }
}

object MillInitMavenAvajeConfigTests extends MillInitMavenTests {

  def tests: Tests = Tests {
    // - multi-module
    // - unsupported test framework
    test - prep("https://github.com/avaje/avaje-config/archive/refs/tags/4.0.zip") { tester =>
      import tester._

      val initRes = eval("init")
      assert(
        initRes.out.contains("generated 4 Mill build file(s)"),
        initRes.isSuccess
      )

      val resolveRes = eval(("resolve", "_"))
      assert(
        resolveRes.out.contains("avaje-config"),
        resolveRes.out.contains("avaje-aws-appconfig"),
        resolveRes.out.contains("avaje-dynamic-logback"),
        resolveRes.isSuccess
      )

      // not sure why this happens
      val compileRes = eval("__.compile")
      assert(
        compileRes.err.contains(
          "avaje-config/src/main/java/module-info.java:5:31: module not found: io.avaje.lang"
        ),
        compileRes.err.contains(
          "avaje-config/src/main/java/module-info.java:6:31: module not found: io.avaje.applog"
        ),
        compileRes.err.contains(
          "avaje-config/src/main/java/module-info.java:7:27: module not found: org.yaml.snakeyaml"
        ),
        compileRes.err.contains(
          "avaje-config/src/main/java/module-info.java:9:27: module not found: io.avaje.spi"
        ),
        !compileRes.isSuccess
      )
    }
  }
}

object MillInitMavenFastExcelTests extends MillInitMavenTests {

  def tests: Tests = Tests {
    // - multi-module
    // - Junit5
    // - module e2e directory and artifact name differ
    test - prep("https://github.com/dhatim/fastexcel/archive/refs/tags/0.18.4.zip") { tester =>
      import tester._

      val initRes = eval("init")
      assert(
        initRes.out.contains("generated 4 Mill build file(s)"),
        initRes.isSuccess
      )

      val resolveRes = eval(("resolve", "_"))
      assert(
        resolveRes.out.contains("fastexcel-reader"),
        resolveRes.out.contains("fastexcel-writer"),
        resolveRes.out.contains("e2e"),
        resolveRes.isSuccess
      )

      // not sure why this happens but pom.xml has a hack to handle JPMS
      val compileRes = eval("__.compile")
      assert(
        compileRes.err.contains(
          "fastexcel-reader/src/main/java/module-info.java:3:32: module not found: org.apache.commons.compress"
        ),
        compileRes.err.contains(
          "fastexcel-reader/src/main/java/module-info.java:4:27: module not found: com.fasterxml.aalto"
        ),
        compileRes.err.contains(
          "fastexcel-writer/src/main/java/module-info.java:2:14: module not found: opczip"
        ),
        !compileRes.isSuccess
      )
    }
  }
}

object MillInitMavenNettyTests extends MillInitMavenTests {

  def tests: Tests = Tests {
    // - multi-module
    // - Junit5
    // - build-helper-maven-plugin
    // - maven-compiler-plugin compilerArgs options
    // - module directory and artifact names differ
    // - multi line description, property <argLine.common>
    // - property <jetty.alpnAgent.path> contains quotes
    test - prep("https://github.com/netty/netty/archive/refs/tags/netty-4.1.114.Final.zip") {
      tester =>
        import tester._

        val initRes = eval(("init", "--process-plugins", "--publish-properties"))
        // cannot resolve native dependencies defined by build extension os-maven-plugin
        assert(
          initRes.out.contains(
            "[codec-http2] dropping classifier ${os.detected.classifier} for dependency io.netty:netty-tcnative:2.0.66.Final"
          ),
          initRes.out.contains(
            "[transport-native-epoll] dropping classifier ${os.detected.classifier} for dependency io.netty:netty-tcnative:2.0.66.Final"
          ),
          initRes.out.contains(
            "[transport-native-kqueue] dropping classifier ${os.detected.classifier} for dependency io.netty:netty-tcnative:2.0.66.Final"
          ),
          initRes.out.contains(
            "[handler] dropping classifier ${os.detected.classifier} for dependency io.netty:netty-tcnative:2.0.66.Final"
          ),
          initRes.out.contains(
            "[example] dropping classifier ${os.detected.classifier} for dependency io.netty:netty-tcnative:2.0.66.Final"
          ),
          initRes.out.contains(
            "[testsuite] dropping classifier ${os.detected.classifier} for dependency io.netty:netty-tcnative:2.0.66.Final"
          ),
          initRes.out.contains(
            "[testsuite-shading] dropping classifier ${os.detected.classifier} for dependency io.netty:netty-tcnative:2.0.66.Final"
          ),
          initRes.out.contains(
            "[transport-blockhound-tests] dropping classifier ${os.detected.classifier} for dependency io.netty:netty-tcnative:2.0.66.Final"
          ),
          initRes.out.contains(
            "[microbench] dropping classifier ${os.detected.classifier} for dependency io.netty:netty-tcnative:2.0.66.Final"
          ),
          initRes.out.contains("generated 47 Mill build file(s)"),
          initRes.isSuccess
        )

        val resolveRes = eval(("resolve", "_"))
        assert(
          resolveRes.out.contains("all"),
          resolveRes.out.contains("dev-tools"),
          resolveRes.out.contains("common"),
          resolveRes.out.contains("buffer"),
          resolveRes.out.contains("codec"),
          resolveRes.out.contains("codec-dns"),
          resolveRes.out.contains("codec-haproxy"),
          resolveRes.out.contains("codec-http"),
          resolveRes.out.contains("codec-http2"),
          resolveRes.out.contains("codec-memcache"),
          resolveRes.out.contains("codec-mqtt"),
          resolveRes.out.contains("codec-redis"),
          resolveRes.out.contains("codec-smtp"),
          resolveRes.out.contains("codec-socks"),
          resolveRes.out.contains("codec-stomp"),
          resolveRes.out.contains("codec-xml"),
          resolveRes.out.contains("resolver"),
          resolveRes.out.contains("resolver-dns"),
          resolveRes.out.contains("resolver-dns-classes-macos"),
          resolveRes.out.contains("resolver-dns-native-macos"),
          resolveRes.out.contains("transport"),
          resolveRes.out.contains("transport-native-unix-common-tests"),
          resolveRes.out.contains("transport-native-unix-common"),
          resolveRes.out.contains("transport-classes-epoll"),
          resolveRes.out.contains("transport-native-epoll"),
          resolveRes.out.contains("transport-classes-kqueue"),
          resolveRes.out.contains("transport-native-kqueue"),
          resolveRes.out.contains("transport-rxtx"),
          resolveRes.out.contains("transport-sctp"),
          resolveRes.out.contains("transport-udt"),
          resolveRes.out.contains("handler"),
          resolveRes.out.contains("handler-proxy"),
          resolveRes.out.contains("handler-ssl-ocsp"),
          resolveRes.out.contains("example"),
          resolveRes.out.contains("testsuite"),
          resolveRes.out.contains("testsuite-autobahn"),
          resolveRes.out.contains("testsuite-http2"),
          resolveRes.out.contains("testsuite-osgi"),
          resolveRes.out.contains("testsuite-shading"),
          resolveRes.out.contains("testsuite-native"),
          resolveRes.out.contains("testsuite-native-image"),
          resolveRes.out.contains("testsuite-native-image-client"),
          resolveRes.out.contains("testsuite-native-image-client-runtime-init"),
          resolveRes.out.contains("transport-blockhound-tests"),
          resolveRes.out.contains("microbench"),
          resolveRes.out.contains("bom"),
          resolveRes.isSuccess
        )

        // compile succeeds for module
        val compileRes = eval("codec.compile")
        assert(
          compileRes.err.contains("compiling 155 Java sources"),
          compileRes.isSuccess
        )

        // test compile fails for module due to missing native dependency
        val testRes = eval("codec.test")
        assert(
          testRes.err.contains(
            "codec/src/test/java/io/netty/handler/codec/NativeImageHandlerMetadataTest.java:20:28: package io.netty.nativeimage does not exist"
          ),
          !testRes.isSuccess
        )

        // publishLocal fails for module due to several Javadoc errors
        val publishLocalRes = eval("codec.publishLocal")
        assert(!publishLocalRes.isSuccess)

        // module with native sources and Java test sources (ie, does not extend MavenModule) is configured for testing
        val testRes1 = eval("transport-native-epoll.test")
        assert(
          // upstream module transport-sctp depends on generated sources in upstream module common
          testRes1.err.contains(
            "transport-sctp/src/main/java/io/netty/handler/codec/sctp/SctpMessageCompletionHandler.java:25:32: package io.netty.util.collection does not exist"
          ),
          !testRes1.isSuccess
        )
    }
  }
}
