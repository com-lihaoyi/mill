import ammonite.ops._
val zippedBytes = scalaj.http.Http()
  .asBytes
  .body