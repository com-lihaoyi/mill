package mill.contrib.sbom

import coursier.Dependency
import os.Path
import upickle.default.macroRW
import upickle.default.ReadWriter

import java.math.BigInteger
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

object CycloneDX {
  case class SbomJson(
      bomFormat: String,
      specVersion: String,
      serialNumber: String,
      version: Int,
      metadata: MetaData,
      components: Seq[Component]
  )

  case class MetaData(timestamp: String = Instant.now().toString)

  case class ComponentHash(alg: String, content: String)

  case class LicenseHolder(license: License)

  case class License(name: String, url: Option[String] = None)

  case class Component(
      `type`: String,
      `bom-ref`: String,
      group: String,
      name: String,
      version: String,
      description: String,
      licenses: Seq[LicenseHolder],
      hashes: Seq[ComponentHash]
  )

  object Component {
    def fromDeps(path: Path, dep: Dependency, licenses: Seq[coursier.Info.License]): Component = {
      val compLicenses = licenses.map { lic =>
        LicenseHolder(License(lic.name, lic.url))
      }
      Component(
        "library",
        s"pkg:maven/${dep.module.organization.value}/${dep.module.name.value}@${dep.version}?type=jar",
        dep.module.organization.value,
        dep.module.name.value,
        dep.version,
        dep.module.orgName,
        compLicenses,
        Seq(ComponentHash("SHA-256", sha256(path)))
      )
    }
  }

  implicit val sbomRW: ReadWriter[SbomJson] = macroRW
  implicit val metaRW: ReadWriter[MetaData] = macroRW
  implicit val componentHashRW: ReadWriter[ComponentHash] = macroRW
  implicit val componentRW: ReadWriter[Component] = macroRW
  implicit val licenceHolderRW: ReadWriter[LicenseHolder] = macroRW
  implicit val licenceRW: ReadWriter[License] = macroRW

  private def sha256(f: Path): String = {
    val md = MessageDigest.getInstance("SHA-256")
    val fileContent = os.read.bytes(f)
    val digest = md.digest(fileContent)
    String.format("%0" + (digest.length << 1) + "x", new BigInteger(1, digest))
  }

  case class SbomHeader(serialNumber: UUID, timestamp: Instant)

}
