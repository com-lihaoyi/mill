//| mvnDeps:
//| - com.lihaoyi::scalasql:0.2.3
//| - com.lihaoyi::scalasql-namedtuples:0.2.3
//| - org.xerial:sqlite-jdbc:3.43.0.0

import scalasql.simple._, SqliteDialect._
import java.time.LocalDate

case class Buyer(id: Int, name: String, dateOfBirth: LocalDate)
object Buyer extends SimpleTable[Buyer]

case class ShippingInfo(id: Int, buyerId: Int, shippingDate: LocalDate)
object ShippingInfo extends SimpleTable[ShippingInfo]

def main(args: Array[String]): Unit = {
  // Initialize database
  val dataSource = new org.sqlite.SQLiteDataSource()
  dataSource.setUrl(s"jdbc:sqlite:./file.db")
  val sqliteClient = new scalasql.DbClient.DataSource(dataSource, config = new scalasql.Config {})

  sqliteClient.transaction { db =>
    db.updateRaw(os.read(os.pwd / "sqlite-customers.sql")) // Populate database from SQL file

    val names = db.run( // Find names of buyers whose shipping date is today or later
      Buyer.select.join(ShippingInfo)(_.id === _.buyerId)
        .filter { case (b, s) => s.shippingDate >= LocalDate.parse(args(0)) }
        .map { case (b, s) => b.name })

    for (name <- names) println(name)
  }
}
