package mill.integration

import mill.testkit.{IntegrationTester, UtestIntegrationTestSuite}
import utest._

abstract class MillInitMavenTests extends UtestIntegrationTestSuite {

  // Github source zip url
  def url: String

  def initMessages(buildFileCount: Int): Seq[String] = Seq(
    s"generated $buildFileCount Mill build file(s)"
  )

  override def integrationTest[T](f: IntegrationTester => T): T =
    super.integrationTest { tester =>
      val zipFile = os.temp(requests.get(url))
      val unzipDir = os.unzip(zipFile, os.temp.dir())
      val sourceDir = os.list(unzipDir).head
      // move fails on Windows, so copy
      for (p <- os.list(sourceDir))
        os.copy.into(p, tester.workspacePath, replaceExisting = true, createFolders = true)
      f(tester)
    }
}

object MillInitMavenJansiTests extends MillInitMavenTests {

  def url: String =
    // - Junit5
    // - maven-compiler-plugin release option
    "https://github.com/fusesource/jansi/archive/refs/tags/jansi-2.4.1.zip"

  val compileMessages: Seq[String] = Seq(
    "compiling 20 Java sources"
  )

  val testMessages: Seq[String] = Seq(
    "Test run finished: 0 failed, 1 ignored, 90 total"
  )

  val publishLocalMessages: Seq[String] = Seq(
    "Publishing Artifact(org.fusesource.jansi,jansi,2.4.1)"
  )

  def tests: Tests = Tests {
    test - integrationTest { tester =>
      import tester._

      val initRes = eval("init")
      assert(
        initMessages(1).forall(initRes.out.contains),
        initRes.isSuccess
      )

      val compileRes = eval("compile")
      assert(
        compileMessages.forall(compileRes.err.contains),
        compileRes.isSuccess
      )

      val testRes = eval("test")
      assert(
        testMessages.forall(testRes.out.contains),
        testRes.isSuccess
      )

      val publishLocalRes = eval("publishLocal")
      assert(
        publishLocalMessages.forall(publishLocalRes.err.contains),
        publishLocalRes.isSuccess
      )
    }

    test("realistic") - integrationTest { tester =>
      import tester._

      val initRes = eval(
        (
          "init",
          "--base-module",
          "JansiModule",
          "--deps-object",
          "Deps",
          "--cache-repository",
          "--process-plugins"
        )
      )
      assert(
        initMessages(1).forall(initRes.out.contains),
        initRes.isSuccess
      )

      val compileRes = eval("compile")
      assert(
        compileMessages.forall(compileRes.err.contains),
        compileRes.isSuccess
      )

      val testRes = eval("test")
      assert(
        testMessages.forall(testRes.out.contains),
        testRes.isSuccess
      )

      val publishLocalRes = eval("publishLocal")
      assert(
        publishLocalMessages.forall(publishLocalRes.err.contains),
        publishLocalRes.isSuccess
      )
    }
  }
}

object MillInitMavenDotEnvTests extends MillInitMavenTests {

  def url: String =
    // - multi-module
    // - TestNg
    // - maven-compiler-plugin release option
    "https://github.com/shyiko/dotenv/archive/refs/tags/0.1.1.zip"

  val resolveModules: Seq[String] = Seq(
    "dotenv",
    "dotenv-guice"
  )

  // JavaModule.JavaTests does not pick compileIvyDeps from outer module
  val compileErrors: Seq[String] = Seq(
    "DotEnvModuleTest.java:3:25: package com.google.inject does not exist"
  )

  def tests: Tests = Tests {
    test - integrationTest { tester =>
      import tester._

      val initRes = eval("init")
      assert(
        initMessages(3).forall(initRes.out.contains),
        initRes.isSuccess
      )

      val resolveRes = eval(("resolve", "_"))
      assert(
        resolveModules.forall(resolveRes.out.contains),
        resolveRes.isSuccess
      )

      val compileRes = eval("__.compile")
      assert(
        compileErrors.forall(compileRes.err.contains),
        !compileRes.isSuccess
      )
    }
  }
}

object MillInitMavenAvajeConfigTests extends MillInitMavenTests {

  def url: String =
    // - multi-module
    // - unsupported test framework
    "https://github.com/avaje/avaje-config/archive/refs/tags/4.0.zip"

  val resolveModules: Seq[String] = Seq(
    "avaje-config",
    "avaje-aws-appconfig",
    "avaje-dynamic-logback"
  )
  // uses moditect-maven-plugin to handle JPMS
  // https://github.com/moditect/moditect
  val compileErrors: Seq[String] = Seq(
    "avaje-config/src/main/java/module-info.java:5:31: module not found: io.avaje.lang",
    "avaje-config/src/main/java/module-info.java:6:31: module not found: io.avaje.applog",
    "avaje-config/src/main/java/module-info.java:7:27: module not found: org.yaml.snakeyaml",
    "avaje-config/src/main/java/module-info.java:9:27: module not found: io.avaje.spi"
  )

  def tests: Tests = Tests {
    test - integrationTest { tester =>
      import tester._

      val initRes = eval("init")
      assert(
        initRes.out.contains("generated 4 Mill build file(s)"),
        initRes.isSuccess
      )

      val resolveRes = eval(("resolve", "_"))
      assert(
        resolveModules.forall(resolveRes.out.contains),
        resolveRes.isSuccess
      )

      // uses moditect-maven-plugin to handle JPMS
      // https://github.com/moditect/moditect
      val compileRes = eval("__.compile")
      assert(
        compileErrors.forall(compileRes.err.contains),
        !compileRes.isSuccess
      )
    }
  }
}

object MillInitMavenNettyTests extends MillInitMavenTests {

  def url: String =
    // - multi-module
    // - Junit5
    // - maven-compiler-plugin compilerArgs options
    // - module directory and artifact names differ
    // - multi line description, properties
    // - property <jetty.alpnAgent.path> contains quotes
    // - defines test dependencies in root pom.xml that get propagated to every module
    "https://github.com/netty/netty/archive/refs/tags/netty-4.1.114.Final.zip"

  val initWarnings: Seq[String] = Seq(
    "[codec-http2] dropping classifier ${os.detected.classifier} for dependency io.netty:netty-tcnative:2.0.66.Final",
    "[transport-native-epoll] dropping classifier ${os.detected.classifier} for dependency io.netty:netty-tcnative:2.0.66.Final",
    "[transport-native-kqueue] dropping classifier ${os.detected.classifier} for dependency io.netty:netty-tcnative:2.0.66.Final",
    "[handler] dropping classifier ${os.detected.classifier} for dependency io.netty:netty-tcnative:2.0.66.Final",
    "[example] dropping classifier ${os.detected.classifier} for dependency io.netty:netty-tcnative:2.0.66.Final",
    "[testsuite] dropping classifier ${os.detected.classifier} for dependency io.netty:netty-tcnative:2.0.66.Final",
    "[testsuite-shading] dropping classifier ${os.detected.classifier} for dependency io.netty:netty-tcnative:2.0.66.Final",
    "[transport-blockhound-tests] dropping classifier ${os.detected.classifier} for dependency io.netty:netty-tcnative:2.0.66.Final",
    "[microbench] dropping classifier ${os.detected.classifier} for dependency io.netty:netty-tcnative:2.0.66.Final"
  )

  val resolveModules: Seq[String] = Seq(
    "all",
    "bom",
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
    "common",
    "dev-tools", // resources only
    "example",
    "handler",
    "handler-proxy",
    "handler-ssl-ocsp",
    "microbench",
    "resolver",
    "resolver-dns",
    "resolver-dns-classes-macos",
    "resolver-dns-native-macos",
    "testsuite", // tests only in src/main/java
    "testsuite-autobahn", // tests only in src/main/java
    "testsuite-http2", // tests only in src/main/java
    "testsuite-native", // tests only in src/test/java
    "testsuite-native-image", // tests only in src/main/java
    "testsuite-native-image-client", // tests only in src/main/java
    "testsuite-native-image-client-runtime-init", // tests only in src/main/java
    "testsuite-osgi", // tests only in src/test/java
    "testsuite-shading", // tests only in src/test/java
    "transport",
    "transport-blockhound-tests", // tests only in src/test/java
    "transport-classes-epoll",
    "transport-classes-kqueue",
    "transport-native-epoll", // C sources
    "transport-native-kqueue", // C sources
    "transport-native-unix-common", // Java and C sources
    "transport-native-unix-common-tests",
    "transport-rxtx",
    "transport-sctp",
    "transport-udt"
  )

  val compileTasksThatSucceed: Seq[String] = Seq(
    "common.compile",
    "dev-tools.compile",
    "testsuite-osgi.compile",
    "buffer.compile",
    "resolver.compile",
    "testsuite-native-image-client-runtime-init.compile",
    "buffer.test.compile",
    "transport.compile",
    "resolver.test.compile",
    "testsuite-native-image-client-runtime-init.test.compile",
    "codec.compile",
    "transport-native-unix-common.compile",
    "transport-rxtx.compile",
    "transport-udt.compile",
    "transport.test.compile",
    "codec-haproxy.compile",
    "codec-memcache.compile",
    "codec-smtp.compile",
    "codec-socks.compile",
    "codec-stomp.compile",
    "codec-xml.compile",
    "handler.compile",
    "transport-native-unix-common-tests.compile",
    "transport-native-unix-common.test.compile",
    "transport-rxtx.test.compile",
    "transport-udt.test.compile",
    "codec-http.compile",
    "transport-native-unix-common-tests.test.compile",
    "handler-proxy.compile",
    "testsuite-autobahn.compile",
    "testsuite-native-image.compile",
    "testsuite-autobahn.test.compile",
    "testsuite-native-image.test.compile"
  )

  val compileTasksThatFail: Seq[String] = Seq(
    /* missing outer compileIvyDeps */
    "common.test.compile",
    /* missing generated sources */
    "transport-sctp.compile",
    "transport-classes-epoll.compile",
    "transport-classes-kqueue.compile",
    "codec-dns.compile",
    "codec-mqtt.compile",
    "codec-redis.compile",
    "codec-http2.compile",
    /* missing native dependency */
    "codec.test.compile",
    "codec-haproxy.test.compile",
    "codec-memcache.test.compile",
    "codec-smtp.test.compile",
    "codec-socks.test.compile",
    "codec-stomp.test.compile",
    "codec-xml.test.compile",
    "handler.test.compile",
    "codec-http.test.compile",
    "handler-proxy.test.compile",
    /* upstream compile fails */
    "codec-mqtt.test.compile",
    "codec-redis.test.compile",
    "codec-dns.test.compile",
    "resolver-dns.compile",
    "transport-sctp.test.compile",
    "transport-classes-epoll.test.compile",
    "transport-native-epoll.compile",
    "transport-classes-kqueue.test.compile",
    "transport-native-kqueue.compile",
    "testsuite.compile",
    "handler-ssl-ocsp.compile",
    "resolver-dns-classes-macos.compile",
    "resolver-dns.test.compile",
    "testsuite-native-image-client.compile",
    "transport-blockhound-tests.compile",
    "testsuite-shading.compile",
    "all.compile",
    "codec-http2.test.compile",
    "example.compile",
    "microbench.compile",
    "testsuite-http2.compile",
    "testsuite-osgi.test.compile",
    "handler-ssl-ocsp.test.compile",
    "resolver-dns-classes-macos.test.compile",
    "resolver-dns-native-macos.compile",
    "testsuite-native-image-client.test.compile",
    "transport-blockhound-tests.test.compile",
    "testsuite.test.compile",
    "transport-native-epoll.test.compile",
    "transport-native-kqueue.test.compile",
    "testsuite-shading.test.compile",
    "all.test.compile",
    "example.test.compile",
    "microbench.test.compile",
    "testsuite-http2.test.compile",
    "resolver-dns-native-macos.test.compile",
    "testsuite-native.compile",
    "testsuite-native.test.compile"
  )

  def tests: Tests = Tests {
    test - integrationTest { tester =>
      import tester._

      val initRes = eval(("init", "--publish-properties"))
      assert(
        // suppressed to avoid CI failure on Windows + JDK 17
        // initWarnings.forall(initRes.out.contains),
        initMessages(47).forall(initRes.out.contains),
        initRes.isSuccess
      )

      val resolveRes = eval(("resolve", "_"))
      assert(
        resolveModules.forall(resolveRes.out.contains),
        resolveRes.isSuccess
      )
      for (task <- compileTasksThatSucceed) {
        assert(eval(task, stdout = os.Inherit, stderr = os.Inherit).isSuccess)
      }
      for (task <- compileTasksThatFail) {
        assert(!eval(task, stdout = os.Inherit, stderr = os.Inherit).isSuccess)
      }
    }
  }
}
