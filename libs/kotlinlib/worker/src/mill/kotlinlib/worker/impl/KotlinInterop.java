package mill.kotlinlib.worker.impl;

import java.util.List;

public class KotlinInterop {
  public static <T> List<T> toKotlinList(T[] args) {
    return kotlin.collections.CollectionsKt.listOf(args);
  }
}
