import spark.Spark.*

fun main() {
    post("/reverse-string") { req, res ->
        req.body().reversed()
    }
}