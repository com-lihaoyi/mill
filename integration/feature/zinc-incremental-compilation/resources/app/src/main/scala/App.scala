package app.src.main.scala

object App {

  val ctx = new SqlMirrorContext(PostgresDialect, SnakeCase)

  case class Person(name: String, age: Int)

  def test = {
    val key = "foo"
    val q = quote {
      query[Person].filter(c => c.name like "% $1" + lift(key) + "%")
    }
    ctx.run(q)
  }

}
