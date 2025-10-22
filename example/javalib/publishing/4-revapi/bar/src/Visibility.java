public class Visibility {
  public int f;

  private class SuperClass {
    public int f;
  }

  public class SubClass extends SuperClass {
    private int f2;
  }
}
