package mill.testng;

import sbt.testing.AnnotatedFingerprint;

public class TestNGFingerprint implements AnnotatedFingerprint {

  public static final TestNGFingerprint instance = new TestNGFingerprint();

  public String annotationName() {
    return "org.testng.annotations.Test";
  }

  public boolean isModule() {
    return false;
  }
}
