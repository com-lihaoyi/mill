package com.helloworld;
import java.time.LocalDate;

public class SampleLogic {

  public static float textSize() {
    return 32f;
  }

  // Uses java.time API (Java 8+) - requires desugaring on minSdk < 26
  public static String today() {
    return LocalDate.now().toString();
  }
}
