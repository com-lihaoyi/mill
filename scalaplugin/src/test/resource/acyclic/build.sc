
val crossVersions = Cross("2.10.6", "2.11.8", "2.12.0")

val acyclic =
  for(crossVersion <- crossVersions)
  yield new ScalaModule{
    def organization = "com.lihaoyi"
    def name = "acyclic"
    def scalaVersion = crossVersion
    def version = "0.1.7"

    override def compileIvyDeps = Seq(
      Dep.Java("org.scala-lang", "scala-compiler", scalaVersion())
    )
  }

val tests =
  for(crossVersion <- crossVersions)
  yield new ScalaModule{
    override def projectDeps = Seq(acyclic(crossVersion))

    override def ivyDeps = Seq(
      Dep("com.lihaoyi", "utest", "0.6.0")
    )
    def test() = T.command{
      TestRunner.apply(
        "mill.UTestFramework",
        runDepClasspath().map(_.path) :+ compile().path,
        Seq(compile().path)
      )
    }
  }
// mill run acyclic(2.10.6)


//
//object acyclic extends crossModule(Seq("2.10.6", "2.11.8", "2.12.0"))(crossVersion =>
//  new ScalaModule{
//    def organization = "com.lihaoyi"
//    def name = "acyclic"
//    def scalaVersion = crossVersion
//    def version = "0.1.7"
//
//    override def compileIvyDeps = Seq(
//      Dep.Java("org.scala-lang", "scala-compiler", scalaVersion())
//    )
//
//    object Tests extends Module{
//      override def projectDeps = Seq(Acyclic(scalaVersion))
//
//      override def ivyDeps = Seq(
//        Dep("com.lihaoyi", "utest", "0.6.0")
//      )
//      def test() = T.command{
//        TestRunner.apply(
//          "mill.UTestFramework",
//          runDepClasspath().map(_.path) :+ compile().path,
//          Seq(compile().path)
//        )
//      }
//    }
//  }
//
//)
//

// Seq("2.10.6", "2.11.8", "2.12.0")
//case class Acyclic(scalaVersion: String = "2.11.8") extends CrossModule{
//  def organization = "com.lihaoyi"
//  def name = "acyclic"
//  def version = "0.1.7"
//
//  override def compileIvyDeps = Seq(
//    Dep.Java("org.scala-lang", "scala-compiler", scalaVersion())
//  )
//}
//
//case class AcyclicTests(scalaVersion: String = "2.11.8") extends CrossModule{
//  override def projectDeps = Seq(Acyclic(scalaVersion))
//
//  override def ivyDeps = Seq(
//    Dep("com.lihaoyi", "utest", "0.6.0")
//  )
//  def test() = T.command{
//    TestRunner.apply(
//      "mill.UTestFramework",
//      runDepClasspath().map(_.path) :+ compile().path,
//      Seq(compile().path)
//    )
//  }
//}

//unmanagedSourceDirectories in Test <+= baseDirectory(_ / "src" / "test" / "resources")
//
//// Sonatype
//publishTo <<= version { (v: String) =>
//  Some("releases"  at "https://oss.sonatype.org/service/local/staging/deploy/maven2")
//}
//
//pomExtra := (
//  <url>https://github.com/lihaoyi/acyclic</url>
//    <licenses>
//      <license>
//        <name>MIT license</name>
//        <url>http://www.opensource.org/licenses/mit-license.php</url>
//      </license>
//    </licenses>
//    <scm>
//      <url>git://github.com/lihaoyi/utest.git</url>
//      <connection>scm:git://github.com/lihaoyi/acyclic.git</connection>
//    </scm>
//    <developers>
//      <developer>
//        <id>lihaoyi</id>
//        <name>Li Haoyi</name>
//        <url>https://github.com/lihaoyi</url>
//      </developer>
//    </developers>
//  )
