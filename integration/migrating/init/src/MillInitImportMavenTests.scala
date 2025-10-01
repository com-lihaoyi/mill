package mill.integration

import mill.integration.IntegrationTesterUtil.*
import mill.javalib.publish.*
import mill.testkit.GitRepoIntegrationTestSuite
import utest.*

object MillInitImportMavenTests extends GitRepoIntegrationTestSuite {
  def tests = Tests {
    test("jansi") - integrationTestGitRepo(
      "https://github.com/fusesource/jansi.git",
      "jansi-2.4.2"
    ) { tester =>
      import tester.*

      val initRes = eval("init")
      assert(initRes.isSuccess)

      val showModuleDeps = eval("__.showModuleDeps").out.linesIterator.toSeq
      assertGoldenLiteral(
        showModuleDeps,
        List("Module dependencies of :", "Module dependencies of test:")
      )

      val repositories = showNamedRepositories(tester)
      assertGoldenLiteral(
        repositories,
        Map(
          "repositories" -> List("https://oss.sonatype.org/content/repositories/snapshots"),
          "test.repositories" -> List()
        )
      )

      val jvmId = showNamedJvmId(tester)
      assertGoldenLiteral(jvmId, Map("jvmId" -> "zulu:11"))

      val mvnDeps = showNamedMvnDeps(tester)
      assertGoldenLiteral(
        mvnDeps,
        Map(
          "mvnDeps" -> List(),
          "test.mvnDeps" -> List(
            "org.junit.jupiter:junit-jupiter:5.7.0",
            "org.junit.jupiter:junit-jupiter-params:5.7.0",
            "info.picocli:picocli-codegen:4.5.2"
          )
        )
      )
      val compileMvnDeps = showNamedCompileMvnDeps(tester)
      assertGoldenLiteral(
        compileMvnDeps,
        Map("compileMvnDeps" -> List(), "test.compileMvnDeps" -> List())
      )
      val runMvnDeps = showNamedRunMvnDeps(tester)
      assertGoldenLiteral(
        runMvnDeps,
        Map("runMvnDeps" -> List(), "test.runMvnDeps" -> List())
      )
      val bomMvnDeps = showNamedBomMvnDeps(tester)
      assertGoldenLiteral(
        bomMvnDeps,
        Map("bomMvnDeps" -> List(), "test.bomMvnDeps" -> List())
      )
      val javacOptions = showNamedJavacOptions(tester)
      assertGoldenLiteral(
        javacOptions,
        Map(
          "javacOptions" -> List("--release", "8", "-encoding", "UTF-8"),
          "test.javacOptions" -> List("--release", "8", "-encoding", "UTF-8")
        )
      )

      val pomParentProject = showNamedPomParentProject(tester)
      assertGoldenLiteral(
        pomParentProject,
        Map("pomParentProject" -> Some(()))
      )
      val pomSettings = showNamedPomSettings(tester)
      assertGoldenLiteral(
        pomSettings,
        Map(
          "pomSettings" -> PomSettings(
            description =
              "Jansi is a java library for generating and interpreting ANSI escape sequences.",
            organization = "org.fusesource.jansi",
            url = "http://fusesource.github.io/jansi",
            licenses = List(
              License(
                id = "",
                name = "Apache License, Version 2.0",
                url = "http://www.apache.org/licenses/LICENSE-2.0.txt",
                isOsiApproved = false,
                isFsfLibre = false,
                distribution = "repo"
              )
            ),
            versionControl = VersionControl(
              browsableRepository = Some("https://github.com/fusesource/jansi"),
              connection = Some("scm:git:git://github.com/fusesource/jansi.git"),
              developerConnection = Some("scm:git:ssh://git@github.com/fusesource/jansi.git"),
              tag = Some("jansi-2.4.2")
            ),
            developers = List(
              Developer(
                id = "chirino",
                name = "Hiram Chirino",
                url = "http://hiramchirino.com",
                organization = None,
                organizationUrl = None
              ),
              Developer(
                id = "gnodet",
                name = "Guillaume Nodet",
                url = "",
                organization = None,
                organizationUrl = None
              )
            ),
            packaging = "jar"
          )
        )
      )
      val publishVersion = showNamedPublishVersion(tester)
      assertGoldenLiteral(publishVersion, Map("publishVersion" -> "2.4.2"))
      val publishProperties = showNamedPublishProperties(tester)
      assertGoldenLiteral(publishProperties, Map("publishProperties" -> Map()))

      val compileRes = tester.eval("compile")
      assert(
        compileRes.isSuccess,
        compileRes.err.contains("compiling 20 Java sources")
      )
    }

    test("netty") - integrationTestGitRepo(
      "https://github.com/netty/netty.git",
      "netty-4.2.6.Final"
    ) { tester =>
      import tester.*

      val initRes = eval("init")
      assert(initRes.isSuccess)

      val showModuleDeps = eval("__.showModuleDeps").out.linesIterator.toSeq
      assertGoldenLiteral(
        showModuleDeps,
        List(
          "Module dependencies of all:",
          "  buffer",
          "  codec-base",
          "  codec",
          "  codec-dns",
          "  codec-haproxy",
          "  codec-compression",
          "  codec-http",
          "  codec-http2",
          "  codec-http3",
          "  codec-memcache",
          "  codec-mqtt",
          "  codec-redis",
          "  codec-smtp",
          "  codec-socks",
          "  codec-stomp",
          "  codec-xml",
          "  codec-protobuf",
          "  codec-marshalling",
          "  common",
          "  handler",
          "  handler-proxy",
          "  handler-ssl-ocsp",
          "  resolver",
          "  resolver-dns",
          "  transport",
          "  transport-rxtx",
          "  transport-sctp",
          "  transport-udt",
          "  transport-classes-epoll",
          "  transport-classes-kqueue",
          "  resolver-dns-classes-macos",
          "  transport-classes-io_uring",
          "  codec-classes-quic",
          "Module dependencies of bom:",
          "Module dependencies of buffer:",
          "  common",
          "  jfr-stub (compile)",
          "  varhandle-stub (compile)",
          "Module dependencies of buffer.test:",
          "  buffer",
          "  jfr-stub",
          "  varhandle-stub",
          "Module dependencies of codec:",
          "  common",
          "  buffer",
          "  transport",
          "  codec-base",
          "  codec-compression",
          "  codec-protobuf",
          "  codec-marshalling",
          "Module dependencies of codec-base:",
          "  common",
          "  buffer",
          "  transport",
          "Module dependencies of codec-base.test:",
          "  codec-base",
          "  transport",
          "Module dependencies of codec-classes-quic:",
          "  common",
          "  buffer",
          "  transport",
          "  codec-base",
          "  handler",
          "  transport-classes-epoll",
          "Module dependencies of codec-compression:",
          "  common",
          "  buffer",
          "  transport",
          "  codec-base",
          "Module dependencies of codec-compression.test:",
          "  codec-compression",
          "  transport",
          "Module dependencies of codec-dns:",
          "  common",
          "  buffer",
          "  transport",
          "  codec-base",
          "Module dependencies of codec-dns.test:",
          "  codec-dns",
          "  transport",
          "Module dependencies of codec-haproxy:",
          "  buffer",
          "  transport",
          "  codec-base",
          "Module dependencies of codec-haproxy.test:",
          "  codec-haproxy",
          "  transport",
          "Module dependencies of codec-http:",
          "  common",
          "  buffer",
          "  transport",
          "  codec-base",
          "  codec-compression",
          "  handler",
          "Module dependencies of codec-http.test:",
          "  codec-http",
          "  transport",
          "Module dependencies of codec-http2:",
          "  common",
          "  buffer",
          "  transport",
          "  codec-base",
          "  codec-compression",
          "  handler",
          "  codec-http",
          "Module dependencies of codec-http2.test:",
          "  codec-http2",
          "  pkitesting",
          "  transport",
          "Module dependencies of codec-http3:",
          "  common",
          "  buffer",
          "  codec-base",
          "  codec-http",
          "  transport",
          "  codec-classes-quic",
          "  codec-native-quic (runtime)",
          "Module dependencies of codec-http3.test:",
          "  codec-http3",
          "Module dependencies of codec-marshalling:",
          "  common",
          "  buffer",
          "  transport",
          "  codec-base",
          "Module dependencies of codec-marshalling.test:",
          "  codec-marshalling",
          "  transport",
          "Module dependencies of codec-memcache:",
          "  common",
          "  buffer",
          "  transport",
          "  codec-base",
          "Module dependencies of codec-memcache.test:",
          "  codec-memcache",
          "  transport",
          "Module dependencies of codec-mqtt:",
          "  common",
          "  buffer",
          "  transport",
          "  codec-base",
          "Module dependencies of codec-mqtt.test:",
          "  codec-mqtt",
          "  transport",
          "Module dependencies of codec-native-quic:",
          "  codec-classes-quic",
          "Module dependencies of codec-native-quic.test:",
          "  codec-native-quic",
          "  transport-classes-epoll",
          "  transport-native-epoll",
          "Module dependencies of codec-protobuf:",
          "  common",
          "  buffer",
          "  transport",
          "  codec-base",
          "Module dependencies of codec-protobuf.test:",
          "  codec-protobuf",
          "  transport",
          "Module dependencies of codec-redis:",
          "  common",
          "  buffer",
          "  transport",
          "  codec-base",
          "Module dependencies of codec-redis.test:",
          "  codec-redis",
          "  transport",
          "Module dependencies of codec-smtp:",
          "  common",
          "  buffer",
          "  transport",
          "  codec-base",
          "Module dependencies of codec-smtp.test:",
          "  codec-smtp",
          "  transport",
          "Module dependencies of codec-socks:",
          "  common",
          "  buffer",
          "  transport",
          "  codec-base",
          "Module dependencies of codec-socks.test:",
          "  codec-socks",
          "  transport",
          "Module dependencies of codec-stomp:",
          "  common",
          "  buffer",
          "  transport",
          "  codec-base",
          "Module dependencies of codec-stomp.test:",
          "  codec-stomp",
          "  transport",
          "Module dependencies of codec-xml:",
          "  buffer",
          "  transport",
          "  codec-base",
          "Module dependencies of codec-xml.test:",
          "  codec-xml",
          "  transport",
          "Module dependencies of common:",
          "  jfr-stub (compile)",
          "  varhandle-stub (compile)",
          "Module dependencies of common.test:",
          "  common",
          "  jfr-stub",
          "  varhandle-stub",
          "  dev-tools",
          "Module dependencies of dev-tools:",
          "Module dependencies of example:",
          "  common",
          "  buffer",
          "  transport",
          "  codec",
          "  handler",
          "  pkitesting",
          "  handler-ssl-ocsp",
          "  transport-sctp",
          "  handler-proxy",
          "  codec-compression",
          "  codec-http",
          "  codec-http2",
          "  codec-memcache",
          "  codec-redis",
          "  codec-socks",
          "  codec-stomp",
          "  codec-mqtt",
          "  codec-haproxy",
          "  codec-dns",
          "  codec-protobuf",
          "  transport-udt",
          "  transport-rxtx",
          "Module dependencies of handler:",
          "  common",
          "  resolver",
          "  buffer",
          "  transport",
          "  transport-native-unix-common",
          "  codec-base",
          "  pkitesting",
          "Module dependencies of handler.test:",
          "  handler",
          "  transport",
          "Module dependencies of handler-proxy:",
          "  common",
          "  buffer",
          "  transport",
          "  codec-base",
          "  codec-socks",
          "  codec-http",
          "  handler",
          "Module dependencies of handler-proxy.test:",
          "  handler-proxy",
          "  transport",
          "  pkitesting",
          "Module dependencies of handler-ssl-ocsp:",
          "  codec-http",
          "  transport",
          "  resolver-dns",
          "Module dependencies of handler-ssl-ocsp.test:",
          "  handler-ssl-ocsp",
          "  pkitesting",
          "Module dependencies of jfr-stub:",
          "Module dependencies of microbench:",
          "  handler",
          "  codec-compression",
          "  codec-http",
          "  codec-http2",
          "  codec-redis",
          "  codec-mqtt",
          "  codec-stomp",
          "  codec-protobuf",
          "  transport-native-epoll",
          "  transport-native-kqueue",
          "  jfr-stub (compile)",
          "Module dependencies of pkitesting:",
          "  common",
          "Module dependencies of pkitesting.test:",
          "  pkitesting",
          "Module dependencies of resolver:",
          "  common",
          "Module dependencies of resolver.test:",
          "  resolver",
          "Module dependencies of resolver-dns:",
          "  common",
          "  buffer",
          "  resolver",
          "  transport",
          "  codec-base",
          "  codec-dns",
          "  handler",
          "Module dependencies of resolver-dns.test:",
          "  resolver-dns",
          "  transport",
          "Module dependencies of resolver-dns-classes-macos:",
          "  common",
          "  resolver-dns",
          "  transport-native-unix-common",
          "Module dependencies of resolver-dns-native-macos:",
          "  resolver-dns-classes-macos",
          "Module dependencies of resolver-dns-native-macos.test:",
          "  resolver-dns-native-macos",
          "Module dependencies of :",
          "Module dependencies of testsuite:",
          "  common",
          "  buffer",
          "  transport",
          "  transport-sctp",
          "  handler",
          "  pkitesting",
          "  codec-http",
          "  transport-udt",
          "Module dependencies of testsuite-autobahn:",
          "  common",
          "  buffer",
          "  transport",
          "  codec-http",
          "Module dependencies of testsuite-http2:",
          "  common",
          "  buffer",
          "  transport",
          "  handler",
          "  codec-http",
          "  codec-http2",
          "Module dependencies of testsuite-jpms:",
          "  buffer",
          "  codec-base",
          "  handler",
          "  pkitesting",
          "  codec-http",
          "  transport-classes-kqueue",
          "  transport-classes-epoll",
          "  transport-classes-io_uring",
          "  codec-http3",
          "  transport-native-epoll",
          "  transport-native-io_uring",
          "  codec-native-quic",
          "Module dependencies of testsuite-jpms.test:",
          "  testsuite-jpms",
          "  codec-http2",
          "  codec-xml",
          "  codec-compression",
          "  codec-mqtt",
          "  codec-smtp",
          "  codec-memcache",
          "  codec-stomp",
          "  codec-socks",
          "  codec-redis",
          "  codec-haproxy",
          "  codec-protobuf",
          "  codec-marshalling",
          "  resolver-dns-classes-macos",
          "  handler-ssl-ocsp",
          "Module dependencies of testsuite-karaf:",
          "  buffer (compile)",
          "  codec-base (compile)",
          "  codec-compression (compile)",
          "  codec-classes-quic (compile)",
          "  codec-dns (compile)",
          "  codec-haproxy (compile)",
          "  codec-http (compile)",
          "  codec-http2 (compile)",
          "  codec-http3 (compile)",
          "  codec-marshalling (compile)",
          "  codec-memcache (compile)",
          "  codec-mqtt (compile)",
          "  codec-redis (compile)",
          "  codec-smtp (compile)",
          "  codec-socks (compile)",
          "  codec-stomp (compile)",
          "  codec-xml (compile)",
          "  common (compile)",
          "  handler (compile)",
          "  handler-proxy (compile)",
          "  handler-ssl-ocsp (compile)",
          "  resolver (compile)",
          "  resolver-dns (compile)",
          "  resolver-dns-classes-macos (compile)",
          "  transport (compile)",
          "  transport-classes-epoll (compile)",
          "  transport-classes-io_uring (compile)",
          "  transport-classes-kqueue (compile)",
          "  transport-native-unix-common (compile)",
          "  transport-rxtx (compile)",
          "  transport-sctp (compile)",
          "Module dependencies of testsuite-native:",
          "  transport-native-epoll",
          "  transport-native-kqueue",
          "  resolver-dns-native-macos",
          "Module dependencies of testsuite-native.test:",
          "  testsuite-native",
          "Module dependencies of testsuite-native-image:",
          "  common",
          "  buffer",
          "  transport",
          "  handler",
          "  codec-http",
          "  transport-classes-epoll",
          "  transport-classes-io_uring",
          "  codec-classes-quic",
          "Module dependencies of testsuite-native-image-client:",
          "  transport",
          "  resolver-dns",
          "Module dependencies of testsuite-native-image-client-runtime-init:",
          "  common",
          "Module dependencies of testsuite-osgi:",
          "Module dependencies of testsuite-shading:",
          "  common",
          "  transport-native-epoll",
          "  handler",
          "Module dependencies of testsuite-shading.test:",
          "  testsuite-shading",
          "Module dependencies of transport:",
          "  common",
          "  buffer",
          "  resolver",
          "Module dependencies of transport.test:",
          "  transport",
          "Module dependencies of transport-blockhound-tests:",
          "  transport",
          "  handler",
          "  pkitesting",
          "  resolver-dns",
          "Module dependencies of transport-blockhound-tests.test:",
          "  transport-blockhound-tests",
          "Module dependencies of transport-classes-epoll:",
          "  common",
          "  buffer",
          "  transport",
          "  transport-native-unix-common",
          "Module dependencies of transport-classes-io_uring:",
          "  common",
          "  buffer",
          "  transport",
          "  transport-native-unix-common",
          "Module dependencies of transport-classes-kqueue:",
          "  common",
          "  buffer",
          "  transport",
          "  transport-native-unix-common",
          "Module dependencies of transport-native-epoll:",
          "  common",
          "  buffer",
          "  transport",
          "  transport-native-unix-common",
          "  transport-classes-epoll",
          "Module dependencies of transport-native-epoll.test:",
          "  transport-native-epoll",
          "  testsuite",
          "  transport-native-unix-common-tests",
          "Module dependencies of transport-native-io_uring:",
          "  common",
          "  buffer",
          "  transport",
          "  transport-native-unix-common",
          "  transport-classes-io_uring",
          "Module dependencies of transport-native-io_uring.test:",
          "  transport-native-io_uring",
          "  testsuite",
          "  transport-native-unix-common-tests",
          "  transport-classes-epoll",
          "  transport-native-epoll",
          "Module dependencies of transport-native-kqueue:",
          "  common",
          "  buffer",
          "  transport",
          "  transport-native-unix-common",
          "  transport-classes-kqueue",
          "Module dependencies of transport-native-kqueue.test:",
          "  transport-native-kqueue",
          "  testsuite",
          "  transport-native-unix-common-tests",
          "Module dependencies of transport-native-unix-common:",
          "  common",
          "  buffer",
          "  transport",
          "Module dependencies of transport-native-unix-common.test:",
          "  transport-native-unix-common",
          "Module dependencies of transport-native-unix-common-tests:",
          "  transport",
          "  transport-native-unix-common",
          "Module dependencies of transport-rxtx:",
          "  buffer",
          "  transport",
          "Module dependencies of transport-sctp:",
          "  common",
          "  buffer",
          "  codec",
          "  transport",
          "Module dependencies of transport-sctp.test:",
          "  transport-sctp",
          "  transport",
          "Module dependencies of transport-udt:",
          "  common",
          "  buffer",
          "  transport",
          "Module dependencies of transport-udt.test:",
          "  transport-udt",
          "Module dependencies of varhandle-stub:"
        )
      )

      val rootPomSettings = showNamedPomSettings(tester, selector = "")
      assertGoldenLiteral(
        rootPomSettings,
        Map(
          "pomSettings" -> PomSettings(
            description =
              "Netty is an asynchronous event-driven network application framework for\n    rapid development of maintainable high performance protocol servers and\n    clients.",
            organization = "io.netty",
            url = "https://netty.io/",
            licenses = List(
              License(
                id = "",
                name = "Apache License, Version 2.0",
                url = "https://www.apache.org/licenses/LICENSE-2.0",
                isOsiApproved = false,
                isFsfLibre = false,
                distribution = ""
              )
            ),
            versionControl = VersionControl(
              browsableRepository = Some("https://github.com/netty/netty"),
              connection = Some("scm:git:git://github.com/netty/netty.git"),
              developerConnection = Some("scm:git:ssh://git@github.com/netty/netty.git"),
              tag = Some("netty-4.2.6.Final")
            ),
            developers = List(
              Developer(
                id = "netty.io",
                name = "The Netty Project Contributors",
                url = "https://netty.io/",
                organization = Some("The Netty Project"),
                organizationUrl = Some("https://netty.io/")
              )
            ),
            packaging = "jar"
          )
        )
      )

      // subsequents checks are limited to a module and its test module to reduce the literal sizes
      val selectorPrefix = "common.__."

      val repositories = showNamedRepositories(tester, selectorPrefix)
      assertGoldenLiteral(
        repositories,
        Map(
          "common.repositories" -> List("https://oss.sonatype.org/content/repositories/snapshots"),
          "common.test.repositories" -> List()
        )
      )

      val jvmId = showNamedJvmId(tester, selectorPrefix)
      assertGoldenLiteral(
        jvmId,
        Map("common.jvmId" -> "zulu:11")
      )

      val mvnDeps = showNamedMvnDeps(tester, selectorPrefix)
      assertGoldenLiteral(
        mvnDeps,
        Map(
          "common.mvnDeps" -> List(
            "org.jctools:jctools-core:4.0.5",
            "org.slf4j:slf4j-api:2.0.13",
            "commons-logging:commons-logging:1.3.4",
            "org.apache.logging.log4j:log4j-1.2-api:2.23.1;exclude=com.sun.jdmk:jmxtools;exclude=com.sun.jmx:jmxri;exclude=javax.jms:jms;exclude=javax.mail:mail",
            "org.apache.logging.log4j:log4j-api:2.23.1",
            "io.projectreactor.tools:blockhound:1.0.13.RELEASE"
          ),
          "common.test.mvnDeps" -> List(
            "org.graalvm.nativeimage:svm:19.3.6",
            "org.jboss:jdk-misc:3.Final",
            "org.jetbrains:annotations:26.0.2",
            "org.apache.logging.log4j:log4j-core:2.23.1",
            "org.mockito:mockito-core:3.6.28",
            "org.junit.jupiter:junit-jupiter-api:5.12.1",
            "org.junit.jupiter:junit-jupiter-engine:5.12.1",
            "org.junit.jupiter:junit-jupiter-params:5.12.1",
            "org.assertj:assertj-core:3.18.0",
            "ch.qos.logback:logback-classic:1.3.14"
          )
        )
      )
      val compileMvnDeps = showNamedCompileMvnDeps(tester, selectorPrefix)
      assertGoldenLiteral(
        compileMvnDeps,
        Map(
          "common.compileMvnDeps" -> List(
            "org.graalvm.nativeimage:svm:19.3.6",
            "org.jboss:jdk-misc:3.Final",
            "org.jetbrains:annotations:26.0.2"
          ),
          "common.test.compileMvnDeps" -> List()
        )
      )
      val runMvnDeps = showNamedCompileMvnDeps(tester, selectorPrefix)
      assertGoldenLiteral(
        runMvnDeps,
        Map(
          "common.compileMvnDeps" -> List(
            "org.graalvm.nativeimage:svm:19.3.6",
            "org.jboss:jdk-misc:3.Final",
            "org.jetbrains:annotations:26.0.2"
          ),
          "common.test.compileMvnDeps" -> List()
        )
      )
      val javacOptions = showNamedJavacOptions(tester, selectorPrefix)
      assertGoldenLiteral(
        javacOptions,
        Map(
          "common.javacOptions" -> List("-source", "1.8", "-target", "1.8"),
          "common.test.javacOptions" -> List("-source", "1.8", "-target", "1.8")
        )
      )

      val pomParentProject = showNamedPomParentProject(tester, selectorPrefix)
      assertGoldenLiteral(
        pomParentProject,
        Map("common.pomParentProject" -> Some(()))
      )
      val pomSettings = showNamedPomSettings(tester, selectorPrefix)
      assertGoldenLiteral(
        pomSettings,
        Map(
          "common.pomSettings" -> PomSettings(
            description = "",
            organization = "",
            url = "",
            licenses = List(),
            versionControl = VersionControl(
              browsableRepository = None,
              connection = None,
              developerConnection = None,
              tag = None
            ),
            developers = List(),
            packaging = "jar"
          )
        )
      )
      val publishVersion = showNamedPublishVersion(tester, selectorPrefix)
      assertGoldenLiteral(publishVersion, Map("common.publishVersion" -> "4.2.6.Final"))

      val commonCompileRes = eval("common.compile")
      assert(
        commonCompileRes.isSuccess,
        commonCompileRes.err.contains("compiling 196 Java sources")
      )

      val commonTestRes = eval("common.test")
      assert(
        !commonTestRes.isSuccess,
        commonTestRes.err.contains("TestEngine with ID 'junit-jupiter' failed to discover tests")
      )
    }
  }
}
