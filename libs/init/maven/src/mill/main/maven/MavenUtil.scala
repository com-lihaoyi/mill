package mill.main.maven

import mill.main.buildgen.ModuleSpec.MvnDep
import org.apache.maven.model.Dependency

import scala.jdk.CollectionConverters.*

object MavenUtil {

  def isBom(dep: Dependency): Boolean = dep.getScope == "import" && dep.getType == "pom"

  def toMvnDep(dep: Dependency): MvnDep = {
    import dep.*
    MvnDep(
      organization = getGroupId,
      name = getArtifactId,
      version = Option(getVersion).getOrElse(""),
      // Sanitize unresolved properties such as ${os.detected.name} to prevent interpolation.
      classifier = Option(getClassifier).map(_.replaceAll("[$]", "")),
      `type` = getType match {
        case null | "jar" | "pom" => None
        case tpe => Some(tpe)
      },
      excludes = getExclusions.asScala.map(x => (x.getGroupId, x.getArtifactId)).toSeq
    )
  }
}
