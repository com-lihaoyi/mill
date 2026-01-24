package app

import io.getquill.*

object App {

  val ctx = new SqlMirrorContext(PostgresDialect, SnakeCase)
  import ctx.*

  case class Person(name: String, age: Int)

  def test = {
    val key = "foo"
    val q = quote {
      query[Person].filter(c => c.name like "% $1" + lift(key) + "%")
    }
    ctx.run(q)
  }

}
