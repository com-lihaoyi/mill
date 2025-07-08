package sources;

public class TestSource {

  public TestSource() { // violation (UnnecessaryConstructor)
  }

  public void doSomething() {
    int x = 5;  // violation (UnusedLocalVariable)
  }
}
