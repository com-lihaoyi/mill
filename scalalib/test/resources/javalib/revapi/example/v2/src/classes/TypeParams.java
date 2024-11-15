public class TypeParams {

  public static class Base<T extends Number, E> {}

  public static class Class<New, B extends Cloneable> extends Base<Double, New> {}
}
