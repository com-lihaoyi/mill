#!/usr/bin/env amm
import ammonite.ops._
import scalaj.http._

@main
def shorten(longUrl: String) = {
  println("shorten longUrl " + longUrl)
  val shortUrl = Http("https://git.io")
    .postForm(Seq("url" -> longUrl))
    .asString
    .headers("Location")
    .head
  println("shorten shortUrl " + shortUrl)
  shortUrl
}
@main
def apply(uploadedFile: Path,
          tagName: String,
          uploadName: String,
          authKey: String): String = {

  val response = Http(s"https://api.github.com/repos/lihaoyi/mill/releases/tags/${tagName}")
    .header("Authorization", "token " + authKey)
    .header("Accept", "application/vnd.github.v3+json")
    .asString
  val body = response.body

  val parsed = ujson.read(body)

  println("Response code: " + response.code)
  println(body)

  val snapshotReleaseId = parsed("id").num.toInt


  val uploadUrl =
    s"https://uploads.github.com/repos/lihaoyi/mill/releases/" +
      s"$snapshotReleaseId/assets?name=$uploadName"

  val res = Http(uploadUrl)
    .header("Content-Type", "application/octet-stream")
    .header("Authorization", "token " + authKey)
    .timeout(connTimeoutMs = 5000, readTimeoutMs = 60000)
    .postData(read.bytes! uploadedFile)
    .asString

  println(res.body)
  val longUrl = ujson.read(res.body)("browser_download_url").str.toString

  println("Long Url " + longUrl)

  val shortUrl = shorten(longUrl)

  println("Short Url " + shortUrl)
  shortUrl
}
