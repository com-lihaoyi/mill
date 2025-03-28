import utest._
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions._

object FooTests extends TestSuite {
  // Initialize Spark
  val spark = SparkSession.builder()
    .appName("Geospatial UDF Tests")
    .master("local[*]")
    .getOrCreate()

  import spark.implicits._

  override def utestAfterAll(): Unit = {
    println("Shutting down Spark")
    spark.stop()
  }

  val tests = Tests {
    test("distanceCalculation") {
      println("+ FooTests.distanceCalculation")
      // Test distance calculation between New York and London
      val testData = Seq(
        (40.7128, -74.0060, 51.5074, -0.1278) // NY to London
      ).toDF("lat1", "lon1", "lat2", "lon2")

      // Register UDF
      spark.udf.register("haversineDistance", Foo.haversineDistance)

      val result = testData
        .selectExpr("haversineDistance(lat1, lon1, lat2, lon2) as distance")
        .collect()
        .head
        .getAs[Double](0)

      // Expected distance is approximately 5570 km
      assert(math.abs(result - 5570) < 100)
    }

    test("polygonContainment") {
      println("+ FooTests.polygonContainment")
      // Test polygon (rectangle around New York City)
      val polygonStr = "(41.0,-75.0);(41.0,-73.0);(40.0,-73.0);(40.0,-75.0);(41.0,-75.0)"

      val testData = Seq(
        (40.7580, -73.9855, "Times Square"), // Inside NYC
        (51.5074, -0.1278, "London") // Outside NYC
      ).toDF("lat", "lon", "location")

      // Register UDF
      spark.udf.register("isInPolygon", Foo.isInPolygon)

      val results = testData
        .selectExpr(s"isInPolygon(lat, lon, '$polygonStr') as is_inside", "location")
        .collect()

      // Times Square should be inside
      assert(results(0).getAs[Boolean]("is_inside") == true)
      // London should be outside
      assert(results(1).getAs[Boolean]("is_inside") == false)
    }

    test("coordinateConversion") {
      println("+ FooTests.coordinateConversion")
      // Test UTM zone calculation
      val testData = Seq(
        (40.7128, -74.0060, "Zone 18N"), // New York
        (51.5074, -0.1278, "Zone 30N"), // London
        (35.6762, 139.6503, "Zone 54N"), // Tokyo
        (-33.8688, 151.2093, "Zone 56S") // Sydney
      ).toDF("lat", "lon", "expected_zone")

      // Register UDF
      spark.udf.register("convertToUTM", Foo.convertToUTM)

      val results = testData
        .selectExpr("convertToUTM(lat, lon) as utm_zone", "expected_zone")
        .collect()

      results.foreach { row =>
        assert(row.getAs[String]("utm_zone") == row.getAs[String]("expected_zone"))
      }
    }

    test("end-to-end workflow") {
      println("+ FooTests.end-to-end workflow")
      // Test the entire workflow with a small dataset
      val testData = Seq(
        (40.7580, -73.9855, "Times Square") // Should be inside service area
      ).toDF("latitude", "longitude", "location")

      val refLat = 40.7580
      val refLon = -73.9855
      val serviceAreaPolygon = "(41.0,-75.0);(41.0,-73.0);(40.0,-73.0);(40.0,-75.0);(41.0,-75.0)"

      // Register UDFs
      spark.udf.register("haversineDistance", Foo.haversineDistance)
      spark.udf.register("isInPolygon", Foo.isInPolygon)
      spark.udf.register("convertToUTM", Foo.convertToUTM)

      val result = testData
        .selectExpr(
          s"haversineDistance(latitude, longitude, $refLat, $refLon) as distance_km",
          s"isInPolygon(latitude, longitude, '$serviceAreaPolygon') as in_service_area",
          "convertToUTM(latitude, longitude) as utm_coords"
        )
        .collect()
        .head

      // Verify results
      assert(result.getAs[Double]("distance_km") < 1.0) // Should be very close to reference point
      assert(result.getAs[Boolean]("in_service_area")) // Should be inside service area
      assert(result.getAs[String]("utm_coords") == "Zone 18N") // Should be in UTM zone 18N
    }
  }
}
