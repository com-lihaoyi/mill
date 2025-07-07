package millbuild

import mill.scalalib.*

/** Dependencies shared between mill meta-build and mill's codebase. */
object SharedDeps {
  def MavenImporter: Seq[Dep] = {
    val mavenVersion = "3.9.9"
    val mavenResolverVersion = "1.9.22"

    Seq(
      // https://mvnrepository.com/artifact/org.apache.maven/maven-embedder
      mvn"org.apache.maven:maven-embedder:$mavenVersion",
      // https://mvnrepository.com/artifact/org.apache.maven.resolver/maven-resolver-connector-basic
      mvn"org.apache.maven.resolver:maven-resolver-connector-basic:$mavenResolverVersion",
      // https://mvnrepository.com/artifact/org.apache.maven.resolver/maven-resolver-supplier
      mvn"org.apache.maven.resolver:maven-resolver-supplier:$mavenResolverVersion",
      // https://mvnrepository.com/artifact/org.apache.maven.resolver/maven-resolver-transport-file
      mvn"org.apache.maven.resolver:maven-resolver-transport-file:$mavenResolverVersion",
      // https://mvnrepository.com/artifact/org.apache.maven.resolver/maven-resolver-transport-http
      mvn"org.apache.maven.resolver:maven-resolver-transport-http:$mavenResolverVersion",
      // https://mvnrepository.com/artifact/org.apache.maven.resolver/maven-resolver-transport-wagon
      mvn"org.apache.maven.resolver:maven-resolver-transport-wagon:$mavenResolverVersion"
    )
  }
}
