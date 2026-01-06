public class Main{
  public static void main(String[] args){
    for (var entry : System.getenv().entrySet()) {
      System.out.println(entry.getKey() + "=" + entry.getValue());
    }
  }
}
