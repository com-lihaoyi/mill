package mill.contrib.codeartifact

import scala.concurrent.duration._

class CodeartifactHttpApi(
    credentials: String,
    readTimeout: Int,
    connectTimeout: Int
) {
  val http = requests.Session(
    readTimeout = readTimeout,
    connectTimeout = connectTimeout,
    maxRedirects = 0,
    check = false
  )

  private val uploadTimeout = 5.minutes.toMillis.toInt

  def upload(uri: String, data: Array[Byte]): requests.Response = {
    http.put(
      uri,
      readTimeout = uploadTimeout,
      headers = Seq("Content-Type" -> "application/octet-stream"),
      auth = new requests.RequestAuth.Basic("aws", credentials),
      data = data
    )
  }
}
