package mill.integration

import mill.constants.Util
import mill.integration.MillInitUtils.{
  SplitTaskResults,
  defaultInitCommand,
  defaultInitCommandWithoutMerge,
  testMillInit
}
import utest.*

import scala.collection.immutable.SortedSet

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

      // Publish things locally, under a directory that shouldn't outlive the test,
      // so that we don't pollute the user's ~/.ivy2/local
      val ivy2Repo = tester.baseWorkspacePath / "ivy2Local"
      val publishLocalRes = eval(("publishLocal", "--localIvyRepo", ivy2Repo.toString))
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
      val initRes = eval(init)
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

      val init = defaultInitCommand
      val initRes = eval(init)
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

      val init = defaultInitCommand
      val initRes = eval(init)
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

    test - integrationTest(url) { tester =>
      // Takes forever on windows and behaves differently from linux/mac
      if (!Util.isWindows) {
        testMillInit(
          tester,
          /*
          `--merge` causes init to fail here:
          ```text
          Exception in thread "main" java.lang.NullPointerException: Cannot invoke "mill.main.buildgen.Tree.nodes$default$1()" because "tree" is null
               at mill.main.buildgen.BuildGenUtil$.writeBuildObject(BuildGenUtil.scala:551)
          ```
           */
          initCommand = defaultInitCommandWithoutMerge :+ "--publish-properties",
          expectedAllSourceFileNums = Map(
            "transport-classes-kqueue.allSourceFiles" -> 29,
            "allSourceFiles" -> 0,
            "testsuite-native.allSourceFiles" -> 0, // tests only
            "transport-native-epoll.test.allSourceFiles" -> 82,
            "resolver.test.allSourceFiles" -> 4,
            "handler.allSourceFiles" -> 175,
            "codec.test.allSourceFiles" -> 88,
            "resolver-dns-classes-macos.allSourceFiles" -> 3,
            "handler-proxy.test.allSourceFiles" -> 9,
            "transport.test.allSourceFiles" -> 55,
            "common.allSourceFiles" -> 164,
            "dev-tools.allSourceFiles" -> 0, // resources only
            "codec-smtp.allSourceFiles" -> 14,
            "example.allSourceFiles" -> 208,
            "resolver.allSourceFiles" -> 20,
            "codec-http2.test.allSourceFiles" -> 70,
            "resolver-dns.allSourceFiles" -> 61,
            "transport-udt.test.allSourceFiles" -> 24,
            "codec-smtp.test.allSourceFiles" -> 4,
            "bom.allSourceFiles" -> 0,
            "codec-redis.test.allSourceFiles" -> 5,
            "transport-native-epoll.allSourceFiles" -> 0, // C sources
            "testsuite-native-image.allSourceFiles" -> 4,
            "codec-mqtt.allSourceFiles" -> 39,
            "transport-udt.allSourceFiles" -> 18,
            "codec-xml.allSourceFiles" -> 17,
            "codec-redis.allSourceFiles" -> 25,
            "transport-native-unix-common.allSourceFiles" -> 31, // Java and C sources
            "resolver-dns-native-macos.allSourceFiles" -> 0,
            "all.allSourceFiles" -> 0,
            "resolver-dns.test.allSourceFiles" -> 18,
            "transport-blockhound-tests.allSourceFiles" -> 0, // tests only
            "codec-stomp.allSourceFiles" -> 17,
            "testsuite-shading.allSourceFiles" -> 0, // tests only
            "codec-stomp.test.allSourceFiles" -> 8,
            "transport-sctp.test.allSourceFiles" -> 6,
            "transport-native-kqueue.allSourceFiles" -> 0, // C sources
            "codec.allSourceFiles" -> 155,
            "codec-socks.allSourceFiles" -> 78,
            "transport-native-kqueue.test.allSourceFiles" -> 62,
            "handler-proxy.allSourceFiles" -> 7,
            "transport-sctp.allSourceFiles" -> 37,
            "handler-ssl-ocsp.allSourceFiles" -> 7,
            "testsuite-osgi.allSourceFiles" -> 0, // tests only
            "handler-ssl-ocsp.test.allSourceFiles" -> 2,
            "buffer.test.allSourceFiles" -> 63,
            "transport-native-unix-common-tests.allSourceFiles" -> 5,
            "microbench.allSourceFiles" -> 143,
            "buffer.allSourceFiles" -> 83,
            "testsuite-shading.test.allSourceFiles" -> 1,
            "codec-socks.test.allSourceFiles" -> 27,
            "codec-haproxy.test.allSourceFiles" -> 5,
            "codec-xml.test.allSourceFiles" -> 2,
            "transport.allSourceFiles" -> 188,
            "codec-dns.test.allSourceFiles" -> 8,
            "codec-http.test.allSourceFiles" -> 98,
            "testsuite-http2.allSourceFiles" -> 6,
            "handler.test.allSourceFiles" -> 82,
            "transport-blockhound-tests.test.allSourceFiles" -> 1,
            "codec-http.allSourceFiles" -> 253,
            "codec-haproxy.allSourceFiles" -> 11,
            "codec-memcache.test.allSourceFiles" -> 9,
            "testsuite-osgi.test.allSourceFiles" -> 2,
            "testsuite-native-image-client.allSourceFiles" -> 2,
            "codec-dns.allSourceFiles" -> 41,
            "common.test.allSourceFiles" -> 55,
            "transport-native-unix-common.test.allSourceFiles" -> 2,
            "resolver-dns-native-macos.test.allSourceFiles" -> 1,
            "testsuite.allSourceFiles" -> 63,
            "transport-classes-epoll.allSourceFiles" -> 33,
            "transport-rxtx.allSourceFiles" -> 6,
            "testsuite-native.test.allSourceFiles" -> 1,
            "codec-memcache.allSourceFiles" -> 34,
            "codec-mqtt.test.allSourceFiles" -> 9,
            "codec-http2.allSourceFiles" -> 127,
            "testsuite-autobahn.allSourceFiles" -> 4,
            "testsuite-native-image-client-runtime-init.allSourceFiles" -> 2
          ),
          expectedCompileTaskResults = Some(SplitTaskResults(
            successful = SortedSet(
              "bom.compile",
              "buffer.compile",
              "buffer.test.compile",
              "codec-haproxy.compile",
              "codec-http.compile",
              "codec-memcache.compile",
              "codec-smtp.compile",
              "codec-socks.compile",
              "codec-stomp.compile",
              "codec-xml.compile",
              "codec.compile",
              "common.compile",
              "compile",
              "dev-tools.compile",
              "handler-proxy.compile",
              "handler.compile",
              "resolver.compile",
              "resolver.test.compile",
              "testsuite-autobahn.compile",
              "testsuite-native-image-client-runtime-init.compile",
              "testsuite-native-image.compile",
              "testsuite-osgi.compile",
              "transport-native-unix-common-tests.compile",
              "transport-native-unix-common.compile",
              "transport-native-unix-common.test.compile",
              "transport-rxtx.compile",
              "transport-udt.compile",
              "transport-udt.test.compile",
              "transport.compile",
              "transport.test.compile"
            ),
            failed = SortedSet(
              "all.compile",
              "codec-dns.compile",
              "codec-dns.test.compile",
              "codec-haproxy.test.compile",
              "codec-http.test.compile",
              "codec-http2.compile",
              "codec-http2.test.compile",
              "codec-memcache.test.compile",
              "codec-mqtt.compile",
              "codec-mqtt.test.compile", /* upstream compile fails */
              "codec-redis.compile",
              "codec-redis.test.compile",
              "codec-smtp.test.compile",
              "codec-socks.test.compile",
              "codec-stomp.test.compile",
              "codec-xml.test.compile",
              "codec.test.compile", /* missing native dependency */
              "common.test.compile", /* missing outer compileIvyDeps */
              "example.compile",
              "handler-proxy.test.compile",
              "handler-ssl-ocsp.compile",
              "handler-ssl-ocsp.test.compile",
              "handler.test.compile",
              "microbench.compile",
              "resolver-dns-classes-macos.compile",
              "resolver-dns-native-macos.compile",
              "resolver-dns-native-macos.test.compile",
              "resolver-dns.compile",
              "resolver-dns.test.compile",
              "testsuite-http2.compile",
              "testsuite-native-image-client.compile",
              "testsuite-native.compile",
              "testsuite-native.test.compile",
              "testsuite-osgi.test.compile",
              "testsuite-shading.compile",
              "testsuite-shading.test.compile",
              "testsuite.compile",
              "transport-blockhound-tests.compile",
              "transport-blockhound-tests.test.compile",
              "transport-classes-epoll.compile",
              "transport-classes-kqueue.compile",
              "transport-native-epoll.compile",
              "transport-native-epoll.test.compile",
              "transport-native-kqueue.compile",
              "transport-native-kqueue.test.compile",
              "transport-sctp.compile", /* missing generated sources */
              "transport-sctp.test.compile"
            )
          )),
          expectedTestTaskResults = Some(SplitTaskResults(
            successful = SortedSet(
              "buffer.test",
              "resolver.test",
              "transport-native-unix-common.test",
              "transport-udt.test"
            ),
            failed = SortedSet(
              "codec-dns.test",
              "codec-haproxy.test",
              "codec-http.test",
              "codec-http2.test",
              "codec-memcache.test",
              "codec-mqtt.test",
              "codec-redis.test",
              "codec-smtp.test",
              "codec-socks.test",
              "codec-stomp.test",
              "codec-xml.test",
              "codec.test",
              "common.test",
              "handler-proxy.test",
              "handler-ssl-ocsp.test",
              "handler.test",
              "resolver-dns-native-macos.test",
              "resolver-dns.test",
              "testsuite-native.test",
              "testsuite-osgi.test",
              "testsuite-shading.test",
              "transport-blockhound-tests.test",
              "transport-native-epoll.test",
              "transport-native-kqueue.test",
              "transport-sctp.test",
              "transport.test"
            )
          ))
        )
      }
    }
  }
}
