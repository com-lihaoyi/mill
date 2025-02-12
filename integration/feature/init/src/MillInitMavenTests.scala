package mill.integration

import mill.client.Util
import utest._

object MillInitMavenJansiTests extends BuildGenTestSuite {

  def tests: Tests = Tests {
    // - single-module
    // - Junit5
    // - maven-compiler-plugin release option
    val url = "https://github.com/fusesource/jansi/archive/refs/tags/jansi-2.4.1.zip"

    test - integrationTest(url) { tester =>
      import tester._

      val initRes = eval("init")
      assert(
        initRes.out.contains(initMessage(1)),
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

    test("realistic") - integrationTest(url) { tester =>
      import tester._

      // set jvmId to test the feature
      val init =
        (
          "init",
          "--base-module",
          "JansiModule",
          "--jvm-id",
          "11",
          "--deps-object",
          "Deps",
          "--cache-repository",
          "--process-plugins"
        )
      val initRes = eval(init, stdout = os.Inherit, stderr = os.Inherit)
      assert(initRes.isSuccess)

      val compileRes = eval("compile")
      assert(compileRes.isSuccess)

      val testRes = eval("test")
      assert(testRes.isSuccess)

      val publishLocalRes = eval("publishLocal")
      assert(publishLocalRes.isSuccess)
    }
  }
}

object MillInitMavenDotEnvTests extends BuildGenTestSuite {

  def tests: Tests = Tests {
    // - multi-module
    // - TestNg
    // - maven-compiler-plugin release option
    val url = "https://github.com/shyiko/dotenv/archive/refs/tags/0.1.1.zip"

    test - integrationTest(url) { tester =>
      import tester._

      val init = ("init", "--base-module", "BaseModule", "--deps-object", "Deps", "--merge")
      val initRes = eval(init, stdout = os.Inherit, stderr = os.Inherit)
      assert(initRes.isSuccess)

      val compileRes = eval("__.compile")
      assert(
        // JavaModule.JavaTests does not pick compileIvyDeps from outer module
        compileRes.err.contains(
          "DotEnvModuleTest.java:3:25: package com.google.inject does not exist"
        ),
        !compileRes.isSuccess
      )
    }
  }
}

object MillInitMavenAvajeConfigTests extends BuildGenTestSuite {

  def tests: Tests = Tests {
    // - multi-module
    // - unsupported test framework
    val url = "https://github.com/avaje/avaje-config/archive/refs/tags/4.0.zip"

    test - integrationTest(url) { tester =>
      import tester._

      val init = ("init", "--base-module", "BaseModule", "--deps-object", "Deps")
      val initRes = eval(init, stdout = os.Inherit, stderr = os.Inherit)
      assert(initRes.isSuccess)

      val compileRes = eval("__.compile")
      assert(
        // uses moditect-maven-plugin to handle JPMS
        // https://github.com/moditect/moditect
        compileRes.err.contains(
          "avaje-config/src/main/java/module-info.java:5:31: module not found: io.avaje.lang"
        ),
        !compileRes.isSuccess
      )
    }
  }
}

object MillInitMavenNettyTests extends BuildGenTestSuite {

  def tests: Tests = Tests {
    // - multi-module
    // - Junit5
    // - maven-compiler-plugin compilerArgs options
    // - module directory and artifact names differ
    // - multi line description, properties
    // - property <jetty.alpnAgent.path> contains quotes
    // - defines test dependencies in root pom.xml that get propagated to every module
    val url = "https://github.com/netty/netty/archive/refs/tags/netty-4.1.114.Final.zip"
    val resolveModules = Seq(
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
      "testsuite",
      "testsuite-autobahn",
      "testsuite-http2",
      "testsuite-native", // tests only
      "testsuite-native-image",
      "testsuite-native-image-client",
      "testsuite-native-image-client-runtime-init",
      "testsuite-osgi", // tests only
      "testsuite-shading", // tests only
      "transport",
      "transport-blockhound-tests", // tests only
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
    val compileTasksThatSucceed = Seq(
      "common.compile",
      "dev-tools.compile",
      "testsuite-osgi.compile",
      "buffer.compile",
      "resolver.compile",
      "testsuite-native-image-client-runtime-init.compile",
      "buffer.test.compile",
      "transport.compile",
      "resolver.test.compile",
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
      "transport-udt.test.compile",
      "codec-http.compile",
      "handler-proxy.compile",
      "testsuite-autobahn.compile",
      "testsuite-native-image.compile"
    )
    val compileTasksThatFail = Seq(
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

    test - integrationTest(url) { tester =>
      // Takes forever on windows and behaves differently from linux/mac
      if (!Util.isWindows) {
        import tester._

        val init =
          ("init", "--base-module", "BaseModule", "--deps-object", "Deps", "--publish-properties")
        val initRes = eval(init, stdout = os.Inherit, stderr = os.Inherit)
        assert(initRes.isSuccess)

        val resolveRes = eval(("resolve", "_"))
        for (mod <- resolveModules) {
          assert(resolveRes.out.contains(mod))
        }

        for (task <- compileTasksThatSucceed) {
          assert(eval(task).isSuccess)
        }
        for (task <- compileTasksThatFail) {
          assert(!eval(task).isSuccess)
        }
      }
    }
  }
}
