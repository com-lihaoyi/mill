package mill.contrib.sbom.upload

import java.util.Base64
import java.nio.charset.StandardCharsets
import mill._
import mill.contrib.sbom.CycloneDXModule
import upickle.default.{ReadWriter, macroRW}

object DependencyTrackModule {
  case class Payload(project: String, bom: String)

  implicit val depTrackPayload: ReadWriter[Payload] = macroRW
}
trait DependencyTrackModule extends CycloneDXModule {
  import DependencyTrackModule._

  def depTrackUrl: T[String]
  def depTrackProjectID: T[String]
  def depTrackApiKey: T[String]

  /**
   * Uploads the generated SBOM to the configured dependency track instance
   */
  def sbomUpload(): Command[Unit] = Task.Command {
    val url = depTrackUrl()
    val projectId = depTrackProjectID()
    val apiKey = depTrackApiKey()

    val bomString = upickle.default.write(sbom())
    val payload = Payload(
      projectId,
      Base64.getEncoder.encodeToString(
        bomString.getBytes(StandardCharsets.UTF_8)
      )
    )
    val body = upickle.default.stream[Payload](payload)
    val bodyBytes = requests.RequestBlob.ByteSourceRequestBlob(body)(identity)
    val r = requests.put(
      s"$url/api/v1/bom",
      headers = Map(
        "Content-Type" -> "application/json",
        "X-API-Key" -> apiKey
      ),
      data = bodyBytes
    )
    assert(r.is2xx)
  }

  def myCmdC(test: String) = Task.Command { println("hi above"); 34 }

}
