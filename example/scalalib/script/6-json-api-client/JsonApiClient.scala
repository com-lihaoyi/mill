def fetchLinks(title: String): Seq[String] = {
  val resp = requests.get.stream(
    "https://en.wikipedia.org/w/api.php",
    params = Seq(
      "action" -> "query",
      "titles" -> title,
      "prop" -> "links",
      "format" -> "json"
    )
  )
  for {
    page <- ujson.read(resp)("query")("pages").obj.values.toSeq
    links <- page.obj.get("links").toSeq
    link <- links.arr
  } yield link("title").str
}

@mainargs.main
def main(startArticle: String, depth: Int) = {
  var seen = Set(startArticle)
  var current = Set(startArticle)
  for (i <- Range(0, depth)) {
    current = current.flatMap(fetchLinks(_)).filter(!seen.contains(_))
    seen = seen ++ current
  }

  pprint.log(seen)
  os.write(os.pwd / "fetched.json", upickle.stream(seen, indent = 4))
}

def main(args: Array[String]): Unit = mainargs.Parser(this).runOrExit(args)
