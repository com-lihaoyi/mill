package mill.contrib.sbom

import mill.*
import mill.javalib.{BoundDep, JavaModule}
import coursier.{Artifacts, Dependency, Resolution, VersionConstraint, core as cs}
import os.Path
import upickle.default.{ReadWriter, macroRW}

import java.math.BigInteger
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

trait CycloneDXModule extends Module {
  import CycloneDX.*

  /** Lists of all components used for this module. */
  def sbomComponents: Task[Seq[Component]]

  /**
   * Each time the SBOM is generated, a new UUID and timestamp are generated
   * Can be overridden to use a more predictable method, eg. for reproducible builds
   */
  def sbomHeader(): SbomHeader = SbomHeader(UUID.randomUUID(), Instant.now())

  /**
   * Generates the SBOM Json for this module, based on the components returned by [[sbomComponents]]
   * @return
   */
  def sbom: T[SbomJson] = Target {
    val header = sbomHeader()
    val components = sbomComponents()

    SbomJson(
      bomFormat = "CycloneDX",
      specVersion = "1.2",
      serialNumber = s"urn:uuid:${header.serialNumber}",
      version = 1,
      metadata = MetaData(timestamp = header.timestamp.toString),
      components = components
    )
  }

  def sbomJsonFile: T[PathRef] = Target {
    val sbomFile = Target.dest / "sbom.json"
    os.write(sbomFile, upickle.default.write(sbom(), indent = 2))
    PathRef(sbomFile)
  }

}
