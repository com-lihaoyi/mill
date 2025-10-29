//| mvnDeps:
//| - org.xerial:sqlite-jdbc:3.43.0.0
//| - org.jetbrains.exposed:exposed-core:0.55.0
//| - org.jetbrains.exposed:exposed-dao:0.55.0
//| - org.jetbrains.exposed:exposed-jdbc:0.55.0
//| - org.jetbrains.exposed:exposed-java-time:0.55.0
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.javatime.date
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.*
import java.time.LocalDate

object BuyerTable : Table("buyer") {
    val id = integer("id").autoIncrement()
    val name = varchar("name", 255)
    val dateOfBirth = date("date_of_birth")
    override val primaryKey = PrimaryKey(id)
}

object ShippingInfoTable : Table("shipping_info") {
    val id = integer("id").autoIncrement()
    val buyerId = integer("buyer_id") references BuyerTable.id
    val shippingDate = date("shipping_date")
    override val primaryKey = PrimaryKey(id)
}

fun main(args: Array<String>) {
    Database.connect("jdbc:sqlite:./file.db", driver = "org.sqlite.JDBC") // Initialize database

    transaction {
        // Populate database from SQL file - split by semicolons and execute each statement
        val sqlContent = Files.readString(Paths.get("sqlite-customers.sql"))
        sqlContent.split(";")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { statement -> exec(statement) }

        // Find names of buyers whose shipping date is today or later
        val query = BuyerTable
            .join(ShippingInfoTable, JoinType.INNER, BuyerTable.id, ShippingInfoTable.buyerId)
            .select(BuyerTable.name)
            .where { ShippingInfoTable.shippingDate greaterEq LocalDate.parse(args[0]) }

        for (row in query) println(row[BuyerTable.name])
    }
}