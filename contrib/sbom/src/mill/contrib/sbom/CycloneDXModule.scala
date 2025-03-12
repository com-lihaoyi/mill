package mill.contrib.sbom

import coursier.core as cs
import coursier.params.ResolutionParams
import mill.*
import mill.javalib.{BoundDep, JavaModule}
import mill.util.Jvm.ResolvedDependency
import os.Path
import upickle.default.{ReadWriter, macroRW}

import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.{Base64, UUID}

object CycloneDXModule {
  case class SBOM_JSON(
      bomFormat: String,
      specVersion: String,
      serialNumber: String,
      version: Int,
      metadata: MetaData,
      components: Seq[Component]
  )
  case class MetaData(timestamp: String = Instant.now().toString)
  case class ComponentHash(alg: String, content: String)
  case class Component(
      `type`: String,
      `bom-ref`: String,
      group: String,
      name: String,
      version: String,
      description: String,
      hashes: Seq[ComponentHash]
  )
  object Component {
    def fromDeps(dependency: ResolvedDependency): Component = {
      val dep = dependency.dependency
      Component(
        "library",
        s"pkg:maven/${dep.module.organization.value}/${dep.module.name.value}@${dep.version}?type=jar",
        dep.module.organization.value,
        dep.module.name.value,
        dep.version,
        dep.module.orgName,
        Seq(ComponentHash("SHA-256", sha256(dependency.path.path)))
      )
    }
  }

  implicit val sbomRW: ReadWriter[SBOM_JSON] = macroRW
  implicit val metaRW: ReadWriter[MetaData] = macroRW
  implicit val componentHashRW: ReadWriter[ComponentHash] = macroRW
  implicit val componentRW: ReadWriter[Component] = macroRW

  case class Payload(project: String, bom: String)
  implicit val depTrackPayload: ReadWriter[Payload] = macroRW

  private def sha256(f: Path): String = {
    val md = MessageDigest.getInstance("SHA-256")
    val fileContent = os.read.bytes(f)
    val digest = md.digest(fileContent)
    String.format("%0" + (digest.length << 1) + "x", new BigInteger(1, digest))
  }
}
trait CycloneDXModule extends JavaModule {
  import CycloneDXModule.*

  def sbomComponents: Task[Agg[Component]] = Task {
    resolvedRunIvyDepsDetails()().map(Component.fromDeps)
  }

  def sbom: T[SBOM_JSON] = Target {
    val resolvedDeps = resolvedRunIvyDepsDetails()()

    val components = resolvedDeps.map { dependency =>
      val dep = dependency.dependency
      Component(
        "library",
        s"pkg:maven/${dep.module.organization.value}/${dep.module.name.value}@${dep.version}?type=jar",
        dep.module.organization.value,
        dep.module.name.value,
        dep.version,
        dep.module.orgName,
        Seq(ComponentHash("SHA-256", sha256(dependency.path.path)))
      )
    }

    SBOM_JSON(
      bomFormat = "CycloneDX",
      specVersion = "1.2",
      serialNumber = s"urn:uuid:${UUID.randomUUID()}",
      version = 1,
      metadata = MetaData(),
      components = components
    )
  }

  def sbomJsonFile: T[PathRef] = Target {
    val sbomFile = Target.dest / "sbom.json"
    os.write(sbomFile, upickle.default.write(sbom()))
    PathRef(sbomFile)
  }

  private def resolvedRunIvyDepsDetails(): Task[Seq[ResolvedDependency]] = Task.Anon {
    millResolver().resolveDepsExtendInfo(
      Seq(
        BoundDep(
          coursierDependency.withConfiguration(cs.Configuration.runtime),
          force = false
        )
      ),
      artifactTypes = Some(artifactTypes()),
      resolutionParamsMapOpt =
        Some((_: ResolutionParams).withDefaultConfiguration(cs.Configuration.runtime))
    )
  }

  def uploadSBom(): Command[Unit] = Task.Command {

    val bomString = upickle.default.write(sbom())
    println(bomString)
    val payload = Payload(
      "ee6ab8bb-a869-4fc1-83e5-d33a91deea8f",
      Base64.getEncoder.encodeToString(
        bomString.getBytes(StandardCharsets.UTF_8)
      )
    )
    val body = upickle.default.stream[Payload](payload)
    val bodyBytes = requests.RequestBlob.ByteSourceRequestBlob(body)(identity)
    val r = requests.put(
      "http://localhost:8081/api/v1/bom",
      headers = Map(
        "Content-Type" -> "application/json",
        "X-API-Key" -> "odt_rnOssPXXYFillE75DAytMZR1vyqoZKKq"
      ),
      data = bodyBytes
    )
    println(r)
  }

}
