package app

import io.getquill._

object App {

  val ctx = new SqlMirrorContext(PostgresDialect, SnakeCase)
  import ctx._

  case class Person(name: String, age: Int)

  def test = {
    val key = "foo"
    val q = quote {
      query[Person].filter(c => c.name like "% $1" + lift(key) + "%")
    }
    ctx.run(q)
  }

}
