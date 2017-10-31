import play.api.libs.json.Json

object Test{
  def main(args: Array[String]): Unit = {
    println(
      Json.prettyPrint(
        Json.toJson(Seq("Hello", "World", "Cow"))
      )
    )
  }
}
