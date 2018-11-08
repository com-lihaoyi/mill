import mill._, mill.scalalib._, mill.scalalib.publish._, mill.scalajslib._


trait UpickleModule extends CrossSbtModule with PublishModule{

  def millSourcePath = build.millSourcePath / "upickle"

  def artifactName = "mill-" + super.artifactName()
  def publishVersion = "0.5.1"

  def pomSettings = PomSettings(
    description = artifactName(),
    organization = "com.lihaoyi",
    url = "https://github.com/lihaoyi/upickle",
    licenses = Seq(License.MIT),
    versionControl = VersionControl.github("lihaoyi", "upickle"),
    developers = Seq(
      Developer("lihaoyi", "Li Haoyi","https://github.com/lihaoyi")
    )
  )
  def scalacPluginIvyDeps = super.scalacPluginIvyDeps() ++ Agg(
    ivy"com.lihaoyi::acyclic:0.1.5"
  )
  def compileIvyDeps = Agg(
    ivy"com.lihaoyi::acyclic:0.1.5",
    ivy"${scalaOrganization()}:scala-reflect:${scalaVersion()}",
    ivy"${scalaOrganization()}:scala-compiler:${scalaVersion()}"
  )
  def ivyDeps = Agg(
    ivy"com.lihaoyi::sourcecode::0.1.3"
  )
  def scalacOptions = Seq("-unchecked",
    "-deprecation",
    "-encoding", "utf8",
    "-feature"
  )

  def sources = T.sources(
    millSourcePath / platformSegment / "src" / "main",
    millSourcePath / "shared" / "src" / "main"
  )

  def generatedSources = T{
    val dir = T.ctx().dest
    val file = dir / "upickle" / "Generated.scala"
    os.makeDir.all(dir / "upickle")
    val tuplesAndCases = (1 to 22).map{ i =>
      def commaSeparated(s: Int => String) = (1 to i).map(s).mkString(", ")
      val writerTypes = commaSeparated(j => s"T$j: Writer")
      val readerTypes = commaSeparated(j => s"T$j: Reader")
      val typeTuple = commaSeparated(j => s"T$j")
      val written = commaSeparated(j => s"writeJs(x._$j)")
      val pattern = commaSeparated(j => s"x$j")
      val read = commaSeparated(j => s"readJs[T$j](x$j)")
      val caseReader =
        if(i == 1) s"f(readJs[Tuple1[T1]](x)._1)"
        else s"f.tupled(readJs[Tuple$i[$typeTuple]](x))"
      (s"""
          implicit def Tuple${i}W[$writerTypes] = makeWriter[Tuple${i}[$typeTuple]](
            x => Js.Arr($written)
          )
          implicit def Tuple${i}R[$readerTypes] = makeReader[Tuple${i}[$typeTuple]](
            validate("Array(${i})"){case Js.Arr($pattern) => Tuple${i}($read)}
          )
          """, s"""
          def Case${i}R[$readerTypes, V]
                       (f: ($typeTuple) => V, names: Array[String], defaults: Array[Js.Value])
            = RCase[V](names, defaults, {case x => $caseReader})
          def Case${i}W[$writerTypes, V]
                       (g: V => Option[Tuple${i}[$typeTuple]], names: Array[String], defaults: Array[Js.Value])
            = WCase[V](names, defaults, x => writeJs(g(x).get))
          """)
    }
    val (tuples, cases) = tuplesAndCases.unzip
    ammonite.ops.write(file, s"""
      package upickle
      import acyclic.file
      import language.experimental.macros
      /**
       * Auto-generated picklers and unpicklers, used for creating the 22
       * versions of tuple-picklers and case-class picklers
       */
      trait Generated extends GeneratedUtil{
        ${tuples.mkString("\n")}
      }
    """)
    Seq(PathRef(dir))
  }

  def platformSegment: String
}

trait UpickleTestModule extends TestModule with ScalaModule{
  def platformSegment: String

  def ivyDeps = Agg(
    ivy"com.lihaoyi::utest::0.5.4",
    ivy"com.lihaoyi::acyclic:0.1.5"
  )

  def sources = T.sources(
    millSourcePath / platformSegment / "src" / "test",
    millSourcePath / "shared" / "src" / "test"
  )
  def testFrameworks = Seq("utest.runner.Framework")
}

object upickleJvm extends Cross[UpickleJvmModule]("2.11.11", "2.12.4")
class UpickleJvmModule(val crossScalaVersion: String) extends UpickleModule{
  def platformSegment = "jvm"

  def ivyDeps = T{
    super.ivyDeps() ++ Seq(ivy"org.spire-math::jawn-parser:0.11.0")
  }
  object test extends Tests with UpickleTestModule{
    def platformSegment = "js"
    def millSourcePath = build.millSourcePath / "upickle"
  }
}

object upickleJs extends Cross[UpickleJsModule]("2.11.11", "2.12.4")
class UpickleJsModule(val crossScalaVersion: String) extends UpickleModule with ScalaJSModule {
  def platformSegment = "js"

  def scalaJSVersion = "0.6.22"
  def scalacOptions = T{
    super.scalacOptions() ++ Seq({
      val a = build.millSourcePath.toString.replaceFirst("[^/]+/?$", "")
      val g = "https://raw.githubusercontent.com/lihaoyi/upickle"
      s"-P:scalajs:mapSourceURI:$a->$g/v${publishVersion()}/"
    })
  }
  object test extends Tests with UpickleTestModule{
    def platformSegment = "js"
    def millSourcePath = build.millSourcePath / "upickle"
  }
}

object test extends ScalaModule{
  def scalaVersion = "2.12.4"
  def moduleDeps = Seq(upickleJvm("2.12.4"))
  def sources = T.sources{millSourcePath}
}
