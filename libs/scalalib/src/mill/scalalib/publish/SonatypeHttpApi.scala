package mill.scalalib.publish

import java.util.Base64

import scala.annotation.tailrec
import scala.concurrent.duration._

import mill.api.BuildInfo
import requests.BaseSession
import ujson.ParseException
import requests.Session

class SonatypeHttpApi(
    uri: String,
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

  private val commonHeaders = Seq(
    "Authorization" -> s"Basic $base64Creds",
    "Accept" -> "application/json",
    "Content-Type" -> "application/json",
    "User-Agent" -> s"mill-${BuildInfo.millVersion}${BaseSession.defaultHeaders.get("User-Agent").map(" (" + _ + ")").getOrElse("")}"
  )

  // https://oss.sonatype.org/nexus-staging-plugin/default/docs/path__staging_profiles.html
  def getStagingProfileUri(groupId: String): String = {
    val response = withRetry(
      http.get(
        s"$uri/staging/profiles",
        headers = commonHeaders
      )
    )

    if (!response.is2xx) {
      throw new Exception(
        s"$uri/staging/profiles returned ${response.statusCode}\n${response.text()}"
      )
    }

    val resourceUri =
      ujson
        .read(response.text())("data")
        .arr
        .find(profile => groupId.split('.').startsWith(profile("name").str.split('.')))
        .map(_("resourceURI").str)

    resourceUri.getOrElse(
      throw new RuntimeException(
        s"Could not find staging profile for groupId: ${groupId}"
      )
    )
  }

  def getStagingRepoState(stagingRepoId: String): String = {
    val response = http.get(
      s"${uri}/staging/repository/${stagingRepoId}",
      headers = commonHeaders
    )
    try {
      ujson.read(response.text())("type").str
    } catch {
      case e: ParseException =>
        throw new RuntimeException(
          s"Could not parse HTTP response. ${e.getMessage()}" + "\n" + s"Raw response: ${response}",
          e
        )
    }
  }

  // https://oss.sonatype.org/nexus-staging-plugin/default/docs/path__staging_profiles_-profileIdKey-_start.html
  def createStagingRepo(profileUri: String, groupId: String): String = {
    val response = withRetry(http.post(
      s"${profileUri}/start",
      headers = commonHeaders,
      data = s"""{"data": {"description": "fresh staging profile for ${groupId}"}}"""
    ))

    if (!response.is2xx) {
      throw new RuntimeException(
        s"$uri/staging/profiles returned ${response.statusCode}\n${response.text()}"
      )
    }

    ujson.read(response.text())("data")("stagedRepositoryId").str
  }

  // https://oss.sonatype.org/nexus-staging-plugin/default/docs/path__staging_profiles_-profileIdKey-_finish.html
  def closeStagingRepo(profileUri: String, repositoryId: String): Boolean = {
    val response = withRetry(
      http.post(
        s"${profileUri}/finish",
        headers = commonHeaders,
        data =
          s"""{"data": {"stagedRepositoryId": "${repositoryId}", "description": "closing staging repository"}}"""
      )
    )

    response.statusCode == 201
  }

  // https://oss.sonatype.org/nexus-staging-plugin/default/docs/path__staging_profiles_-profileIdKey-_promote.html
  def promoteStagingRepo(profileUri: String, repositoryId: String): Boolean = {
    val response = withRetry(
      http.post(
        s"${profileUri}/promote",
        headers = commonHeaders,
        data =
          s"""{"data": {"stagedRepositoryId": "${repositoryId}", "description": "promote staging repository"}}"""
      )
    )

    response.statusCode == 201
  }

  // https://oss.sonatype.org/nexus-staging-plugin/default/docs/path__staging_profiles_-profileIdKey-_drop.html
  def dropStagingRepo(profileUri: String, repositoryId: String): Boolean = {
    val response = withRetry(
      http.post(
        s"${profileUri}/drop",
        headers = commonHeaders,
        data =
          s"""{"data": {"stagedRepositoryId": "${repositoryId}", "description": "drop staging repository"}}"""
      )
    )

    response.statusCode == 201
  }

  private val uploadTimeout = 5.minutes.toMillis.toInt

  def upload(uri: String, data: Array[Byte]): requests.Response = {
    http.put(
      uri,
      readTimeout = uploadTimeout,
      headers = Seq(
        "Content-Type" -> "application/binary",
        "Authorization" -> s"Basic ${base64Creds}"
      ),
      data = data
    )
  }

  @tailrec
  private def withRetry(
      request: => requests.Response,
      retries: Int = 10,
      initialTimeout: FiniteDuration = 100.millis
  ): requests.Response = {
    val resp = request
    if (resp.is5xx && retries > 0) {
      Thread.sleep(initialTimeout.toMillis)
      withRetry(request, retries - 1, initialTimeout * 2)
    } else {
      resp
    }
  }

  private def base64(s: String) =
    new String(Base64.getEncoder.encode(s.getBytes))

}
