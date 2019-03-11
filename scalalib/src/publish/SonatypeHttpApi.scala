package mill.scalalib.publish

import java.util.Base64



import scala.concurrent.duration._
import scalaj.http.{BaseHttp, HttpOptions, HttpRequest, HttpResponse}

object PatientHttp
    extends BaseHttp(
      options = Seq(
        HttpOptions.connTimeout(5.seconds.toMillis.toInt),
        HttpOptions.readTimeout(1.minute.toMillis.toInt),
        HttpOptions.followRedirects(false)
      )
    )

class SonatypeHttpApi(uri: String, credentials: String) {

  private val base64Creds = base64(credentials)

  private val commonHeaders = Seq(
    "Authorization" -> s"Basic $base64Creds",
    "Accept" -> "application/json",
    "Content-Type" -> "application/json"
  )

  // https://oss.sonatype.org/nexus-staging-plugin/default/docs/path__staging_profiles.html
  def getStagingProfileUri(groupId: String): String = {
    val response = withRetry(
      PatientHttp(s"$uri/staging/profiles").headers(commonHeaders))
        .throwError

    val resourceUri =
      ujson
        .read(response.body)("data")
        .arr
        .find(profile =>
          groupId.split('.').startsWith(profile("name").str.split('.')))
        .map(_("resourceURI").str.toString)

    resourceUri.getOrElse(
      throw new RuntimeException(
        s"Could not find staging profile for groupId: ${groupId}")
    )
  }

  def getStagingRepoState(stagingRepoId: String): String = {
    val response = PatientHttp(s"${uri}/staging/repository/${stagingRepoId}")
      .option(HttpOptions.readTimeout(60000))
      .headers(commonHeaders)
      .asString
      .throwError

    ujson.read(response.body)("type").str.toString
  }

  // https://oss.sonatype.org/nexus-staging-plugin/default/docs/path__staging_profiles_-profileIdKey-_start.html
  def createStagingRepo(profileUri: String, groupId: String): String = {
    val response = withRetry(PatientHttp(s"${profileUri}/start")
      .headers(commonHeaders)
      .postData(
        s"""{"data": {"description": "fresh staging profile for ${groupId}"}}"""))
      .throwError

    ujson.read(response.body)("data")("stagedRepositoryId").str.toString
  }

  // https://oss.sonatype.org/nexus-staging-plugin/default/docs/path__staging_profiles_-profileIdKey-_finish.html
  def closeStagingRepo(profileUri: String, repositoryId: String): Boolean = {
    val response = withRetry(
      PatientHttp(s"${profileUri}/finish")
        .headers(commonHeaders)
        .postData(
          s"""{"data": {"stagedRepositoryId": "${repositoryId}", "description": "closing staging repository"}}"""
        ))

    response.code == 201
  }

  // https://oss.sonatype.org/nexus-staging-plugin/default/docs/path__staging_profiles_-profileIdKey-_promote.html
  def promoteStagingRepo(profileUri: String, repositoryId: String): Boolean = {
    val response = withRetry(
      PatientHttp(s"${profileUri}/promote")
        .headers(commonHeaders)
        .postData(
          s"""{"data": {"stagedRepositoryId": "${repositoryId}", "description": "promote staging repository"}}"""
        ))

    response.code == 201
  }

  // https://oss.sonatype.org/nexus-staging-plugin/default/docs/path__staging_profiles_-profileIdKey-_drop.html
  def dropStagingRepo(profileUri: String, repositoryId: String): Boolean = {
    val response = withRetry(
      PatientHttp(s"${profileUri}/drop")
        .headers(commonHeaders)
        .postData(
          s"""{"data": {"stagedRepositoryId": "${repositoryId}", "description": "drop staging repository"}}"""
        ))

    response.code == 201
  }

  private val uploadTimeout = 5.minutes.toMillis.toInt

  def upload(uri: String, data: Array[Byte]): HttpResponse[String] = {
    PatientHttp(uri)
      .option(HttpOptions.readTimeout(uploadTimeout))
      .method("PUT")
      .headers(
        "Content-Type" -> "application/binary",
        "Authorization" -> s"Basic ${base64Creds}"
      )
      .put(data)
      .asString
  }

  private def withRetry(request: HttpRequest,
                        retries: Int = 10): HttpResponse[String] = {
    val resp = request.asString
    if (resp.is5xx && retries > 0) {
      Thread.sleep(500)
      withRetry(request, retries - 1)
    } else {
      resp
    }
  }

  private def base64(s: String) =
    new String(Base64.getEncoder.encode(s.getBytes))

}
