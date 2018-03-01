import ammonite.ops._
import $ivy.`com.typesafe::mima-reporter:0.1.18`
import com.typesafe.tools.mima.lib.MiMaLib
import com.typesafe.tools.mima.core._
import mill._, scalalib._

trait MiMa extends ScalaModule with PublishModule {
  def previousVersions = T {
    scalaVersion().split('.')(1) match {
      case "10" => Seq("2.6.0")
      case "11" => Seq("2.6.0")
      case "12" => Seq("2.6.0")
      case _    => Nil
    }
  }

  def mimaBinaryIssueFilters: Seq[ProblemFilter] = Seq.empty

  def previousDeps = T {
    Agg.from(previousVersions().map { version =>
      Dep.Java(
        pomSettings().organization,
        artifactId(),
        version,
        cross = false
      )
    })
  }

  def previousArtifacts = T {
    resolveDeps(previousDeps)().filter(_.path.segments.contains(artifactId()))
  }

  def mimaReportBinaryIssues: T[List[(String, List[String])]] = T {
    val currentClassfiles = compile().classes.path
    val classpath = runClasspath()

    val lib = {
      com.typesafe.tools.mima.core.Config.setup("sbt-mima-plugin", Array.empty)
      val cpstring = classpath
        .map(_.path)
        .filter(exists)
        .mkString(System.getProperty("path.separator"))
      new MiMaLib(
        com.typesafe.tools.mima.core.reporterClassPath(cpstring)
      )
    }

    previousArtifacts().toList.map { path =>
      val problems =
        lib.collectProblems(path.path.toString, currentClassfiles.toString)
      path.path.toString -> problems.filter { problem =>
        mimaBinaryIssueFilters.forall(_.apply(problem))
      }.map(_.description("current"))
    }
  }

}
