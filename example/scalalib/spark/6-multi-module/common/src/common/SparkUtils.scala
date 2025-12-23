package common

import org.apache.spark.sql.SparkSession

/** Shared utilities for Spark applications */
object SparkUtils {

  /** Creates a SparkSession configured for local development */
  def createLocalSession(appName: String): SparkSession = {
    SparkSession.builder()
      .appName(appName)
      .master("local[*]")
      .config("spark.sql.shuffle.partitions", "4")
      .getOrCreate()
  }

  /** Common data model for users */
  case class User(user: Int, name: String, age: Int, active: Boolean)

  /** Sample user data for demonstration */
  val sampleUsers: Seq[User] = Seq(
    User(1, "Alice", 28, active = true),
    User(2, "Bob", 35, active = true),
    User(3, "Charlie", 42, active = false)
  )
}
