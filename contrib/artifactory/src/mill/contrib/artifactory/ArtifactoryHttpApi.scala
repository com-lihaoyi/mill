package mill.contrib.artifactory

import java.util.Base64

import scala.concurrent.duration._
import requests.Session

class ArtifactoryHttpApi(
    credentials: String,
    readTimeout: Int,
    connectTimeout: Int
) {
  val http: Session = requests.Session(
    readTimeout = readTimeout,
    connectTimeout = connectTimeout,
    maxRedirects = 0,
    check = false
  )

  private val base64Creds = base64(credentials)
  private val uploadTimeout = 5.minutes.toMillis.toInt

  // https://www.jfrog.com/confluence/display/JFROG/Artifactory+REST+API#ArtifactoryRESTAPI-DeployArtifact
  def upload(uri: String, data: Array[Byte]): requests.Response = {
    http.put(
      uri,
      readTimeout = uploadTimeout,
      headers = Seq(
        "Content-Type" -> "application/java-archive",
        "Authorization" -> s"Basic ${base64Creds}"
      ),
      data = data
    )
  }

  private def base64(s: String) =
    String(Base64.getEncoder.encode(s.getBytes))
}
