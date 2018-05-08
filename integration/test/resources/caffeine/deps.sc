
import mill._
import mill.scalalib._

object versions{
  val akka = "2.5.11"
  val commonsCompress = "1.16.1"
  val commonsLang3 = "3.7"
  val config = "1.3.3"
  val errorProne = "2.2.0"
  val fastutil = "8.1.1"
  val flipTables = "1.0.2"
  val guava = "24.1-jre"
  val javapoet = "1.10.0"
  val jcache = "1.1.0"
  val jsr305 = "3.0.2"
  val jsr330 = "1"
  val univocityParsers = "2.6.2"
  val ycsb = "0.13.0"
  val xz = "1.8"
}
object testVersions{
  val awaitility = "3.1.0"
  val easymock = "3.5.1"
  val hamcrest = "2.0.0.0"
  val jcacheTck = "1.1.0"
  val jctools = "2.1.2"
  val junit = "4.12"
  val mockito = "2.18.0"
  val paxExam = "4.11.0"
  val testng = "6.14.3"
  val truth = "0.24"
}
object benchmarkVersions{
  val cache2k = "1.0.2.Final"
  val collision = "0.3.3"
  val concurrentlinkedhashmap = "1.4.2"
  val ehcache3 = "3.5.2"
  val elasticSearch = "6.2.3"
  val expiringMap = "0.5.8"
  val jackrabbit = "1.8.2"
  val jamm = "0.3.2"
  val javaObjectLayout = "0.9"
  val jmh = ".2"
  val koloboke = "0.6.8"
  val ohc = "0.6.1"
  val rapidoid = "5.5.4"
  val slf4j = "1.7.25"
  val tcache = "1.0.5"
}
object libraries{
  val akka = ivy"com.typesafe.akka:akka-actor_2.12:${versions.akka}"
  val commonsCompress = ivy"org.apache.commons:commons-compress:${versions.commonsCompress}"
  val commonsLang3 = ivy"org.apache.commons:commons-lang3:${versions.commonsLang3}"
  val config = ivy"com.typesafe:config:${versions.config}"
  val errorProneAnnotations = ivy"com.google.errorprone:error_prone_annotations:${versions.errorProne}"
  val errorProneCore = ivy"com.google.errorprone:error_prone_core:${versions.errorProne}"
  val fastutil = ivy"it.unimi.dsi:fastutil:${versions.fastutil}"
  val flipTables = ivy"com.jakewharton.fliptables:fliptables:${versions.flipTables}"
  val guava = ivy"com.google.guava:guava:${versions.guava}"
  val javapoet = ivy"com.squareup:javapoet:${versions.javapoet}"
  val jcache = ivy"javax.cache:cache-api:${versions.jcache}"
  val jsr305 = ivy"com.google.code.findbugs:jsr305:${versions.jsr305}"
  val jsr330 = ivy"javax.inject:javax.inject:${versions.jsr330}"
  val univocityParsers = ivy"com.univocity:univocity-parsers:${versions.univocityParsers}"
  val ycsb = ivy"com.github.brianfrankcooper.ycsb:core:${versions.ycsb}"
  val xz = ivy"org.tukaani:xz:${versions.xz}"
}
object testLibraries{
  val awaitility = ivy"org.awaitility:awaitility:${testVersions.awaitility}"
    .excludeOrg("org.hamcrest")

  val easymock = ivy"org.easymock:easymock:${testVersions.easymock}"

  val guavaTestLib = ivy"com.google.guava:guava-testlib:${versions.guava}"
    .excludeOrg("com.google.truth", "junit")

  val hamcrest = ivy"org.hamcrest:java-hamcrest:${testVersions.hamcrest}"
  val jcacheGuice = ivy"org.jsr107.ri:cache-annotations-ri-guice:${versions.jcache}"
  val jcacheTck = ivy"javax.cache:cache-tests:${testVersions.jcacheTck}"
  val jcacheTckTests = ivy"javax.cache:cache-tests:${testVersions.jcacheTck}"
  val jctools = ivy"org.jctools:jctools-core:${testVersions.jctools}"
  val junit = ivy"junit:junit:${testVersions.junit}"

  val mockito = ivy"org.mockito:mockito-core:${testVersions.mockito}"
    .excludeOrg("org.hamcrest")

  val osgiCompile = Seq(
    ivy"org.apache.felix:org.apache.felix.framework:5.6.10",
    ivy"org.ops4j.pax.exam:pax-exam-junit4:${testVersions.paxExam}"
  )

  val osgiRuntime = Seq(
    ivy"org.ops4j.pax.exam:pax-exam-container-native:${testVersions.paxExam}",
    ivy"org.ops4j.pax.exam:pax-exam-link-mvn:${testVersions.paxExam}",
    ivy"org.ops4j.pax.url:pax-url-aether:2.5.4"
  )

  val testng = Seq(
    ivy"org.testng:testng:${testVersions.testng}"
      .excludeOrg("junit", "guice"),
    ivy"com.google.inject:guice:4.2.0"
  )

  val truth = ivy"com.google.truth:truth:${testVersions.truth}"
}
object benchmarkLibraries{
  val cache2k = ivy"org.cache2k:cache2k-core:${benchmarkVersions.cache2k}"
  val collision = ivy"systems.comodal:collision:${benchmarkVersions.collision}"
  val concurrentlinkedhashmap = ivy"com.googlecode.concurrentlinkedhashmap:concurrentlinkedhashmap-lru:${benchmarkVersions.concurrentlinkedhashmap}"
  val ehcache3 = ivy"org.ehcache:ehcache:${benchmarkVersions.ehcache3}"

  val elasticSearch = ivy"org.elasticsearch:elasticsearch:${benchmarkVersions.elasticSearch}"
    .excludeOrg("org.apache.lucene")

  val expiringMap = ivy"net.jodah:expiringmap:${benchmarkVersions.expiringMap}"

  val jackrabbit = ivy"org.apache.jackrabbit:oak-core:${benchmarkVersions.jackrabbit}"
    .excludeOrg("junit")

  val jamm = ivy"com.github.jbellis:jamm:${benchmarkVersions.jamm}"
  val javaObjectLayout = ivy"org.openjdk.jol:jol-cli:${benchmarkVersions.javaObjectLayout}"

  val koloboke = Seq(
    ivy"net.openhft:koloboke-api-jdk8:${benchmarkVersions.koloboke}",
    ivy"net.openhft:koloboke-impl-jdk8:${benchmarkVersions.koloboke}",
  )

  val ohc = ivy"org.caffinitas.ohc:ohc-core-j8:${benchmarkVersions.ohc}"
  val rapidoid = ivy"org.rapidoid:rapidoid-commons:${benchmarkVersions.rapidoid}"
  val slf4jNop = ivy"org.slf4j:slf4j-nop:${benchmarkVersions.slf4j}"
  val tcache = ivy"com.trivago:triava:${benchmarkVersions.tcache}"
}