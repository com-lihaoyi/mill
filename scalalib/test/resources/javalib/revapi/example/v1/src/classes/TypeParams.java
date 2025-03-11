public class TypeParams {

  public static class Base<T> {}

  public static class Class<A, B extends String, T> extends Base<T> {}
}
