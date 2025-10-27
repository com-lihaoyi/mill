//| mvnDeps: [com.sparkjava:spark-core:2.9.4]
import static spark.Spark.*;

public class MinimalApplication {
  public static void main(String[] args) {
    // Define a POST route at /do-thing
    post("/do-thing", (request, response) -> {
      // Reverse the request body text and return it
      String body = request.body();
      return new StringBuilder(body).reverse().toString();
    });
  }
}
