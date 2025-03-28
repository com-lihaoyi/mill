import org.apache.spark.sql.{SparkSession, DataFrame}
import org.apache.spark.sql.functions._
import org.apache.spark.sql.types._
import breeze.linalg._
import breeze.numerics._

object Foo {
  def main(args: Array[String]): Unit = {
    val spark = SparkSession.builder()
      .appName("Geospatial UDF Example")
      .master("local[*]")
      .getOrCreate()

    import spark.implicits._

    // Register UDFs
    spark.udf.register("haversineDistance", haversineDistance)
    spark.udf.register("isInPolygon", isInPolygon)
    spark.udf.register("convertToUTM", convertToUTM)

    // Create sample data
    val locationData = Seq(
      (40.7128, -74.0060, "New York"), // New York
      (51.5074, -0.1278, "London"), // London
      (35.6762, 139.6503, "Tokyo"), // Tokyo
      (48.8566, 2.3522, "Paris"), // Paris
      (40.4168, -3.7038, "Madrid") // Madrid
    ).toDF("latitude", "longitude", "city")

    // Define service area polygon (simplified rectangle around New York)
    val serviceAreaPolygon = Array(
      (41.0, -75.0), // Top-left
      (41.0, -73.0), // Top-right
      (40.0, -73.0), // Bottom-right
      (40.0, -75.0), // Bottom-left
      (41.0, -75.0) // Close the polygon
    )

    // Reference point (Times Square, NY)
    val refLat = 40.7580
    val refLon = -73.9855

    // Process and analyze data
    val result = locationData
      .withColumn("distance_km", expr(s"haversineDistance(latitude, longitude, $refLat, $refLon)"))
      .withColumn(
        "in_service_area",
        expr(s"isInPolygon(latitude, longitude, '${serviceAreaPolygon.mkString(";")}')")
      )
      .withColumn("utm_coords", expr("convertToUTM(latitude, longitude)"))

    // Display results
    println("Geospatial Analysis Results:")
    result.select(
      col("city"),
      format_number(col("latitude"), 4).as("latitude"),
      format_number(col("longitude"), 4).as("longitude"),
      format_number(col("distance_km"), 2).as("distance_km"),
      col("in_service_area"),
      col("utm_coords")
    ).show(false)

    spark.stop()
  }

  // Haversine formula for calculating distance between two points
  val haversineDistance = udf { (lat1: Double, lon1: Double, lat2: Double, lon2: Double) =>
    val R = 6371.0 // Earth's radius in km

    val dLat = math.toRadians(lat2 - lat1)
    val dLon = math.toRadians(lon2 - lon1)

    val a = math.sin(dLat / 2) * math.sin(dLat / 2) +
      math.cos(math.toRadians(lat1)) * math.cos(math.toRadians(lat2)) *
      math.sin(dLon / 2) * math.sin(dLon / 2)

    val c = 2 * math.atan2(math.sqrt(a), math.sqrt(1 - a))

    R * c
  }

  // Check if a point is inside a polygon using ray casting algorithm
  val isInPolygon = udf { (lat: Double, lon: Double, polygonStr: String) =>
    val polygon = polygonStr.split(";")
      .map(_.stripPrefix("(").stripSuffix(")"))
      .map(coord => {
        val parts = coord.split(",")
        (parts(0).toDouble, parts(1).toDouble)
      })

    var inside = false
    var j = polygon.length - 1
    for (i <- polygon.indices) {
      if (
        (polygon(i)._2 > lon) != (polygon(j)._2 > lon) &&
        (lat < (polygon(j)._1 - polygon(i)._1) * (lon - polygon(i)._2) /
          (polygon(j)._2 - polygon(i)._2) + polygon(i)._1)
      ) {
        inside = !inside
      }
      j = i
    }
    inside
  }

  // Convert lat/lon to UTM coordinates (simplified version)
  val convertToUTM = udf { (lat: Double, lon: Double) =>
    val zone = ((lon + 180) / 6).toInt + 1
    val hemisphere = if (lat >= 0) "N" else "S"
    s"Zone ${zone}${hemisphere}"
  }
}
