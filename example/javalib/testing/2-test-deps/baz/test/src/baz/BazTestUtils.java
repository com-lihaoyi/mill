package baz;

public class BazTestUtils {

  public static void bazAssertEquals(Object x, Object y) {
    System.out.println("Using BazTestUtils.bazAssertEquals");
    if (!x.equals(y)) {
      throw new AssertionError("Expected " + y + " but got " + x);
    }
  }
}
