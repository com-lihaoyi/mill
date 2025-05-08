package millbuild

/** Publishable module which contains strictly handled API. */
trait MillStableScalaModule extends MillPublishScalaModule /*with Mima*/ {
  /*
  import com.github.lolgab.mill.mima._
//  override def mimaBinaryIssueFilters: T[Seq[ProblemFilter]] = Seq()

  def mimaPreviousVersions: T[Seq[String]] = Settings.mimaBaseVersions

  def mimaPreviousArtifacts: T[Seq[Dep]] = Task {
    Agg.from(
      Settings.mimaBaseVersions
        .filter(v => !skipPreviousVersions().contains(v))
        .map({ version =>
          val patchedSuffix = {
            val base = artifactSuffix()
            version match {
              case s"0.$minor.$_" if minor.toIntOption.exists(_ < 12) =>
                base match {
                  case "_3" => "_2.13"
                  case s"_3_$suffix" => s"_2.13_$suffix"
                  case _ => base
                }
              case _ => base
            }
          }
          val patchedId = artifactName() + patchedSuffix
          mvn"${pomSettings().organization}:${patchedId}:${version}"
        })
    )
  }

  def mimaExcludeAnnotations = Seq("mill.api.internal", "mill.api.experimental")
//  def mimaCheckDirection = CheckDirection.Backward
  def skipPreviousVersions: T[Seq[String]] = T {
    T.log.info("Skipping mima for previous versions (!!1000s of errors due to Scala 3)")
    mimaPreviousVersions() // T(Seq.empty[String])
  }*/
}