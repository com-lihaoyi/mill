// Reproduction of issue https://github.com/com-lihaoyi/mill/issues/2579
// and issue https://github.com/com-lihaoyi/mill/issues/2582

// mill plugins
import $ivy.`de.tototec::de.tobiasroeser.mill.vcs.version::0.4.0`
import $ivy.`de.tototec::de.tobiasroeser.mill.integrationtest::0.7.1`
import $ivy.`com.lihaoyi::mill-contrib-scoverage:`
import $ivy.`com.github.lolgab::mill-mima::0.0.23`

// imports
import mill._
import mill.contrib.scoverage.ScoverageModule
import mill.define.{Command, Sources, Target, Task, TaskModule}
import mill.scalalib._
import mill.scalalib.api.ZincWorkerUtil
import mill.scalalib.publish._
import de.tobiasroeser.mill.integrationtest._
import de.tobiasroeser.mill.vcs.version._
import com.github.lolgab.mill.mima.Mima
import scala.util.{Properties, Try}

val baseDir = build.millSourcePath

trait Deps {
  def millPlatform: String
  def millVersion: String
  def scalaVersion: String = "2.13.11"
  def testWithMill: Seq[String]

  def mimaPreviousVersions: Seq[String] = Seq()

  val millMain = ivy"com.lihaoyi::mill-main:${millVersion}"
  val scalaTest = ivy"org.scalatest::scalatest:3.2.16"
  val scoverageVersion = "2.0.10"
  val scoverageRuntime = ivy"org.scoverage::scalac-scoverage-runtime:${scoverageVersion}"
}

object Deps_0_11 extends Deps {
  override def millPlatform = "0.11"
  override def millVersion = "0.11.0" // scala-steward:off
  override def testWithMill = Seq(millVersion)
  override def mimaPreviousVersions = Seq()
}

val crossDeps: Seq[Deps] = Seq(Deps_0_11)
val millApiVersions = crossDeps.map(x => x.millPlatform -> x)
val millItestVersions = crossDeps.flatMap(x => x.testWithMill.map(_ -> x))

/** Shared configuration. */
trait BaseModule extends CrossScalaModule with PublishModule with ScoverageModule with Mima {
  def millApiVersion: String
  def deps: Deps = millApiVersions.toMap.apply(millApiVersion)
  def crossScalaVersion = deps.scalaVersion
  override def artifactSuffix: T[String] = s"_mill${deps.millPlatform}_${artifactScalaVersion()}"

  override def ivyDeps = T {
    Agg(ivy"${scalaOrganization()}:scala-library:${scalaVersion()}")
  }

  def publishVersion = VcsVersion.vcsState().format()
  override def versionScheme: T[Option[VersionScheme]] = T(Option(VersionScheme.EarlySemVer))

  override def mimaPreviousVersions = deps.mimaPreviousVersions
  override def mimaPreviousArtifacts: Target[Agg[Dep]] = T {
    val md = artifactMetadata()
    Agg.from(
      mimaPreviousVersions().map(v => ivy"${md.group}:${md.id}:${v}")
    )
  }

  override def sources: Sources = T.sources {
    Seq(PathRef(millSourcePath / "src")) ++
      (ZincWorkerUtil.matchingVersions(millApiVersion) ++
        ZincWorkerUtil.versionRanges(millApiVersion, crossDeps.map(_.millPlatform)))
        .map(p => PathRef(millSourcePath / s"src-${p}"))
  }

  override def javacOptions = {
    (if (Properties.isJavaAtLeast(9)) Seq("--release", "8") else Seq("-source", "1.8", "-target", "1.8")) ++
      Seq("-encoding", "UTF-8", "-deprecation")
  }

  override def scalacOptions = Seq("-target:jvm-1.8", "-encoding", "UTF-8", "-deprecation")

  def pomSettings = T {
    PomSettings(
      description = "Mill plugin to derive a version from (last) git tag and edit state",
      organization = "de.tototec",
      url = "https://github.com/lefou/mill-vcs-version",
      licenses = Seq(License.`Apache-2.0`),
      versionControl = VersionControl.github("lefou", "mill-vcs-version"),
      developers = Seq(Developer("lefou", "Tobias Roeser", "https.//github.com/lefou"))
    )
  }

  override def scoverageVersion = deps.scoverageVersion

}

/* The actual mill plugin compilied against different mill APIs. */
object core extends Cross[CoreCross](millApiVersions.map(_._1))
trait CoreCross extends BaseModule with Cross.Module[String] {
  override def millApiVersion: String = crossValue

  override def artifactName = "de.tobiasroeser.mill.vcs.version"

  override def skipIdea: Boolean = deps != crossDeps.head

  override def compileIvyDeps = Agg(deps.millMain)

  object test extends ScoverageTests with TestModule.ScalaTest {
    override def ivyDeps = Agg(deps.scalaTest, deps.millMain)
  }
}

/** Integration tests. */
object itest extends Cross[ItestCross](millItestVersions.map(_._1)) with TaskModule {
  override def defaultCommandName(): String = "test"
  def testCached: T[Seq[TestCase]] = itest(millItestVersions.map(_._1).head).testCached
  def test(args: String*): Command[Seq[TestCase]] = itest(millItestVersions.map(_._1).head).test(args: _*)
}
trait ItestCross extends MillIntegrationTestModule with Cross.Module[String] {

  def millItestVersion = crossValue

  val millApiVersion = millItestVersions.toMap.apply(millItestVersion).millPlatform
  def deps: Deps = millApiVersions.toMap.apply(millApiVersion)

  override def millSourcePath: os.Path = super.millSourcePath / os.up
  override def millTestVersion = millItestVersion
  override def pluginsUnderTest = Seq(core(millApiVersion))

  /** Replaces the plugin jar with a scoverage-enhanced version of it. */
  override def pluginUnderTestDetails: Task[Seq[(PathRef, (PathRef, (PathRef, (PathRef, (PathRef, Artifact)))))]] =
    Target.traverse(pluginsUnderTest) { p =>
      val jar = p match {
        case p: ScoverageModule => p.scoverage.jar
        case p                  => p.jar
      }
      jar zip (p.sourceJar zip (p.docJar zip (p.pom zip (p.ivy zip p.artifactMetadata))))
    }

  override def testInvocations: Target[Seq[(PathRef, Seq[TestInvocation.Targets])]] = T {
    testCases().map { pathref =>
      pathref.path.last match {
        case "01-simple" =>
          pathref -> Seq(
            TestInvocation.Targets(Seq("-d", "verify1")),
            TestInvocation.Targets(Seq("de.tobiasroeser.mill.vcs.version.VcsVersion/vcsState")),
            TestInvocation.Targets(Seq("changeSomething")),
            TestInvocation.Targets(Seq("verify2"))
          )
        case "no-git" =>
          pathref -> Seq(
            // setting GIT_DIR explicitly disables repository discovery
            TestInvocation.Targets(targets = Seq("-d", "verify"), env = Map("GIT_DIR" -> "."))
          )
        case _ =>
          pathref -> Seq(TestInvocation.Targets(Seq("-d", "verify")))
      }
    }
  }

  override def perTestResources = T.sources { Seq(generatedSharedSrc()) }
  def generatedSharedSrc = T {
    os.write(
      T.dest / "shared.sc",
      s"""import $$ivy.`${deps.scoverageRuntime.dep.module.organization.value}::${deps.scoverageRuntime.dep.module.name.value}:${deps.scoverageRuntime.dep.version}`
         |""".stripMargin
    )
    PathRef(T.dest)
  }

}
