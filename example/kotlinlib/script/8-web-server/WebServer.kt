//| mvnDeps: [com.sparkjava:spark-core:2.9.4]
import spark.Spark.*

fun main() {
    post("/reverse-string") { req, res ->
        req.body().reversed()
    }
}