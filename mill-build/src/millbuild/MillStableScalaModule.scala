package millbuild
//import com.github.lolgab.mill.mima._
import mill._, scalalib._

/** Publishable module which contains strictly handled API. */
trait MillStableScalaModule extends MillPublishScalaModule /*with Mima*/ {

//  override def mimaBinaryIssueFilters: T[Seq[ProblemFilter]] = Seq.empty[ProblemFilter]
//
//  def mimaPreviousVersions: T[Seq[String]] = Settings.mimaBaseVersions
//
//  def mimaPreviousArtifacts: T[Seq[Dep]] = Task {
//    Settings.mimaBaseVersions
//      .map({ version =>
//        val patchedSuffix = {
//          val base = artifactSuffix()
//          version match {
//            case s"0.$minor.$_" if minor.toIntOption.exists(_ < 12) =>
//              base match {
//                case "_3" => "_2.13"
//                case s"_3_$suffix" => s"_2.13_$suffix"
//                case _ => base
//              }
//            case _ => base
//          }
//        }
//        val patchedId = artifactName() + patchedSuffix
//        mvn"${pomSettings().organization}:${patchedId}:${version}"
//      })
//    Seq.empty[Dep]
//  }
//
//  def mimaExcludeAnnotations = Seq("mill.api.internal.internal", "mill.api.experimental")
}
