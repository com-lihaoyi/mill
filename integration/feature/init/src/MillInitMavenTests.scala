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
    val url = "https://github.com/fusesource/jansi/archive/refs/tags/jansi-2.4.1.zip"

    test - prep(url) { tester =>
      import tester._

      val initRes = eval("init")
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

    test("realistic") - prep(url) { tester =>
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

object MillInitMavenOwnerTests extends MillInitMavenTests {

  private val compileTasks = Seq(
    "owner.compile",
    "owner-site.compile",
    "owner-extras.compile",
    "owner-java8.compile",
    "owner-examples.owner-examples-hotreload.compile",
    "owner.test.compile",
    "owner-extras.test.compile",
    "owner-java8.test.compile",
    "owner-assembly.compile",
    "owner-java8-extras.compile",
    "owner-java8-extras.test.compile"
  )

  private val modules = Seq(
    "owner",
    "owner-assembly",
    "owner-examples",
    "owner-extras",
    "owner-java8-extras",
    "owner-java8",
    "owner-site"
  )

  private val publishing = Seq(
    "Publishing Artifact(org.aeonbits.owner,owner-parent,1.0.12)",
    "Publishing Artifact(org.aeonbits.owner,owner-site,1.0.12)",
    "Publishing Artifact(org.aeonbits.owner,owner-examples,1.0.12)",
    "Publishing Artifact(org.aeonbits.owner,owner-assembly,1.0.12)",
    "Publishing Artifact(org.aeonbits.owner,owner-examples-owner-examples-hotreload,1.0.12)",
    "Publishing Artifact(org.aeonbits.owner,owner-java8,1.0.12)",
    "Publishing Artifact(org.aeonbits.owner,owner-extras,1.0.12)",
    "Publishing Artifact(org.aeonbits.owner,owner-java8-extras,1.0.12)",
    "Publishing Artifact(org.aeonbits.owner,owner,1.0.12)",
  )

  def tests: Tests = Tests {

    // - multi-level multi-module
    // - Junit4
    // - maven-compiler-plugin release option
    val url = "https://github.com/matteobaccan/owner/archive/refs/tags/owner-1.0.12.zip"

    test - prep(url) {
      tester =>
        import tester._

        val initRes = eval("init")
        assert(
          initRes.out.contains("generated 9 Mill build file(s)"),
          initRes.isSuccess
        )

        val resolveRes = eval(("resolve", "_"))
        assert(
          modules.forall(resolveRes.out.contains),
          resolveRes.isSuccess
        )

        assert(
          compileTasks.forall(eval(_).isSuccess)
        )

        val testRes = eval("__.test")
        assert(
          testRes.err.contains("owner.test.test 3 tests failed"), // bad release?
          !testRes.isSuccess
        )

        val publishLocalRes = eval("__.publishLocal")
        assert(
          publishing.forall(publishLocalRes.err.contains),
          publishLocalRes.isSuccess
        )
    }

    test("realistic") - prep(url) {
      tester =>
        import tester._

        val initRes = eval(
          (
            "init",
            "--base-module",
            "OwnerModule",
            "--deps-object",
            "Deps",
            "--merge",
            "--cache-repository",
            "--process-plugins"
          )
        )
        assert(
          initRes.out.contains("generated 1 Mill build file(s)"),
          initRes.isSuccess
        )

        val resolveRes = eval(("resolve", "_"))
        assert(
          modules.forall(resolveRes.out.contains),
          resolveRes.isSuccess
        )

        assert(
          compileTasks.forall(eval(_).isSuccess)
        )

        val testRes = eval("__.test")
        assert(
          testRes.err.contains("owner.test.test 3 tests failed"), // bad release?
          !testRes.isSuccess
        )

        val publishLocalRes = eval("__.publishLocal")
        assert(
          publishing.forall(publishLocalRes.err.contains),
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
    val url = "https://github.com/shyiko/dotenv/archive/refs/tags/0.1.1.zip"

    test - prep(url) { tester =>
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
      // assert(
      //   !testRes.isSuccess
      // )
    }
  }
}

object MillInitMavenAvajeConfigTests extends MillInitMavenTests {

  def tests: Tests = Tests {
    // - multi-module
    // - unsupported test framework
    val url = "https://github.com/avaje/avaje-config/archive/refs/tags/4.0.zip"

    test - prep(url) { tester =>
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

      // uses moditect-maven-plugin to handle JPMS
      // https://github.com/moditect/moditect
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
    val url = "https://github.com/dhatim/fastexcel/archive/refs/tags/0.18.4.zip"

    test - prep(url) { tester =>
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

      // pom.xml has custom profiles to handle JPMS
      // https://github.com/dhatim/fastexcel/blob/de56e786a1fe29351e2f8dc1d81b7cdd9196de4a/pom.xml#L251
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

  private val compileTasksThatSucceed = Array(
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

  private val compileTasksThatFail = Array(
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

  private val modules = Array(
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

  private val modulesWithNativeClassifier = Array(
    "codec-http2",
    "transport-native-epoll",
    "transport-native-kqueue",
    "handler",
    "example",
    "testsuite",
    "testsuite-shading",
    "transport-blockhound-tests",
    "microbench"
  )

  def tests: Tests = Tests {
    // - multi-module
    // - Junit5
    // - maven-compiler-plugin compilerArgs options
    // - module directory and artifact names differ
    // - multi line description, properties
    // - property <jetty.alpnAgent.path> contains quotes
    // - defines test dependencies in root pom.xml that get propagated to every module
    val url = "https://github.com/netty/netty/archive/refs/tags/netty-4.1.114.Final.zip"

    test - prep(url) {
      tester =>
        import tester._

        val initRes = eval(("init", "--publish-properties"))
        assert(
          modulesWithNativeClassifier.forall(module =>
            initRes.out.contains(
              // cannot resolve native dependencies defined by build extension os-maven-plugin
              s"[$module] dropping classifier $${os.detected.classifier} for dependency io.netty:netty-tcnative:2.0.66.Final"
            )
          ),
          initRes.out.contains("generated 47 Mill build file(s)"),
          initRes.isSuccess
        )

        val resolveRes = eval(("resolve", "_"))
        assert(
          modules.forall(resolveRes.out.contains),
          resolveRes.isSuccess
        )

        assert(
          compileTasksThatSucceed.forall(eval(_).isSuccess),
          compileTasksThatFail.forall(!eval(_).isSuccess)
        )
    }
  }
}
