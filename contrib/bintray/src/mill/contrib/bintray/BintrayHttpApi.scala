package mill.contrib.bintray

import mill.api.Logger

import java.time.{ZoneOffset, ZonedDateTime}
import java.time.format.DateTimeFormatter
import java.util.Base64

import scala.concurrent.duration._

class BintrayHttpApi(
    owner: String,
    repo: String,
    credentials: String,
    readTimeout: Int,
    connectTimeout: Int,
    log: Logger
) {
  val http = requests.Session(
    readTimeout = readTimeout,
    connectTimeout = connectTimeout,
    maxRedirects = 0,
    check = false
  )

  private val uploadTimeout = 5.minutes.toMillis.toInt

  def now = ZonedDateTime.now(ZoneOffset.UTC)

  // https://www.jfrog.com/confluence/display/BT/Bintray+REST+API#BintrayRESTAPI-UploadContent
  def upload(
      pkg: String,
      version: String,
      path: String,
      contentType: String,
      data: Array[Byte]
  ): requests.Response =
    send(s"Uploading $path") {
      http.put(
        s"${Paths.upload(pkg, version)}/$path",
        readTimeout = uploadTimeout,
        headers = Seq(
          "Content-Type" -> contentType,
          "Authorization" -> Auth.basic
        ),
        data = data
      )
    }

  def createVersion(
      pkg: String,
      version: String,
      releaseDate: ZonedDateTime = now,
      description: String = ""
  ): requests.Response =
    send(s"Creating version $version") {
      http.post(
        Paths.version(pkg),
        headers = Seq(
          "Content-Type" -> ContentTypes.json,
          "Authorization" -> Auth.basic
        ),
        data =
          s"""{
             |  "desc": "$description",
             |  "released": "${releaseDate.format(DateTimeFormatter.ISO_INSTANT)}",
             |  "name": "$version"
             |}""".stripMargin
      )
    }

  def publish(pkg: String, version: String): requests.Response =
    send(s"Publishing version $version") {
      http.post(
        Paths.publish(pkg, version),
        headers = Seq(
          "Authorization" -> Auth.basic
        )
      )
    }

  private def send(description: String)(request: => requests.Response) = {
    log.info(description)
    val response = request
    if (!response.is2xx && !response.is3xx) {
      log.error(s"$description failed")
    }
    response
  }

  object Paths {
    val root = "https://api.bintray.com"
    def upload(pkg: String, version: String) = s"$root/content/$owner/$repo/$pkg/$version"
    def publish(pkg: String, version: String) = s"$root/content/$owner/$repo/$pkg/$version/publish"
    def version(pkg: String) = s"$root/packages/$owner/$repo/$pkg/versions"
  }

  object ContentTypes {
    val jar = "application/java-archive"
    val xml = "application/xml"
    val json = "application/json"
  }

  object Auth {
    val basic = s"Basic ${base64(credentials)}"
  }

  private def base64(s: String) =
    new String(Base64.getEncoder.encode(s.getBytes))
}
