import static spark.Spark.*;

public class WebServer {
  public static void main(String[] args) {
    post("/reverse-string", (request, response) -> {
      String body = request.body();
      return new StringBuilder(body).reverse().toString();
    });
  }
}
