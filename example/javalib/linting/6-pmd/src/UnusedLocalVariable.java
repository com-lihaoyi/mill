
public class UnusedLocalVariable {
  public void foo() {
    int unused = 0; // violation
    System.out.println("Hello PMD!");
  }
}
