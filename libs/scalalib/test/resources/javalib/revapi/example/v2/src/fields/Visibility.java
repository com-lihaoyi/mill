public class Visibility {
  protected int f;

  private class SuperClass {
    private int f;
  }

  public class SubClass extends SuperClass {
    public int f2;
  }
}
