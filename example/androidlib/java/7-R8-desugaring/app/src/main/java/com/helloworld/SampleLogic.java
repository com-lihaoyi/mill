package com.helloworld;

import java.time.LocalDate;
import java.util.function.*;

public class SampleLogic {

  public static float textSize() {
    return 32f;
  }

  // Uses java.time API (Java 8+) - requires desugaring on minSdk < 26
  public static Supplier<String> today() {
    return () -> LocalDate.now().toString();
  }
}
