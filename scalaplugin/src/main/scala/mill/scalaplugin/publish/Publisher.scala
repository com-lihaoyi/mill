package mill.scalaplugin.publish

import java.io.File
import java.nio.file.{Files, StandardCopyOption, StandardOpenOption}
import java.security.MessageDigest

import ammonite.ops._
import mill.eval.PathRef

object LocalPublisher {

  val root: Path = {
    val ivy2 = {
      // a bit touchy on Windows... - don't try to manually write down the URI with s"file://..."
      val str = new File(sys.props("user.home") + "/.ivy2/").toString
      if (str.endsWith("/")) str else str + "/"
    }
    Path(ivy2 + "local/")
  }

  def publish(
    file: PathRef,
    artifact: Artifact,
    dependencies: Seq[Dependency]
  ): String = {
    val sett = PomSettings("mill", "url", Seq.empty, SCM("", ""), Seq.empty)
    val f = file.path
    val pomData = PomFile.generatePom(artifact, dependencies, sett)
    val ivyData = IvyFile.generateIvy(artifact, dependencies, sett)


    val dir = root/artifact.group/artifact.id/artifact.version

    val jars = dir/"jars"
    val poms = dir/"poms"
    val ivys = dir/"ivys"

    val fileName = artifact match {
      case j: JavaArtifact => j.name
      case sa: ScalaArtifact =>
        val postfix = {
          val arr = sa.scalaVersion.split('.')
          val erased = if (arr.length > 2) arr.dropRight(1) else arr
          erased.mkString(".")
        }
        s"${sa.name}_$postfix"
    }

    Seq(dir, jars, poms, ivys).foreach(d => if (!d.toIO.exists()) mkdir(d))

    Files.copy(f.toNIO, (dir/"jars"/s"$fileName.jar").toNIO, StandardCopyOption.REPLACE_EXISTING)
    Files.write((dir/"poms"/s"$fileName.pom").toNIO, pomData.getBytes(),
      StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING
    )
    Files.write((dir/"ivys"/"ivy.xml").toNIO, ivyData.getBytes(),
      StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING
    )

    dir.toString()
  }

}
