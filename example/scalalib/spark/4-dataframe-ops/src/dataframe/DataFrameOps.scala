package dataframe

import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.functions._

object DataFrameOps {

  case class Employee(id: Int, name: String, department: String, salary: Int)

  def main(args: Array[String]): Unit = {
    val spark = SparkSession.builder()
      .appName("DataFrameOperations")
      .master("local[*]")
      .getOrCreate()

    import spark.implicits._

    // Create a DataFrame from a sequence of case class instances
    val employees = Seq(
      Employee(1, "Alice", "Finance", 75000),
      Employee(2, "Bob", "Eng", 85000),
      Employee(3, "Charlie", "Eng", 90000),
      Employee(4, "Diana", "Finance", 72000),
      Employee(5, "Eve", "HR", 65000)
    ).toDF()

    // Display the original DataFrame
    println("=== Original DataFrame ===")
    employees.show()

    // Select specific columns
    println("=== Select name and salary ===")
    employees.select($"name", $"salary").show()

    // Filter rows based on a condition
    println("=== Filter salary > 70000 ===")
    employees.filter($"salary" > 70000).show()

    // Add a new computed column
    println("=== Add bonus column ===")
    employees
      .withColumn("bonus", $"salary" * 0.1)
      .show()

    // Group by and aggregate
    println("=== Group by department ===")
    employees
      .groupBy($"department")
      .agg(
        count("*").alias("employee_count"),
        avg($"salary").alias("avg_salary"),
        max($"salary").alias("max_salary")
      )
      .show()

    // Sort by column (descending)
    println("=== Sorted by salary desc ===")
    employees.orderBy($"salary".desc).show()

    // Demonstrate join with another DataFrame
    val departments = Seq(
      ("Eng", "Engineering"),
      ("Finance", "Finance & Accounting"),
      ("HR", "Human Resources")
    ).toDF("dept_code", "dept_full_name")

    println("=== Join with department names ===")
    employees
      .join(departments, employees("department") === departments("dept_code"))
      .select($"name", $"dept_full_name", $"salary")
      .show()

    spark.stop()
  }
}
