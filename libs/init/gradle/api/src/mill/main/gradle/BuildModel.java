package mill.main.gradle;

import java.io.Serializable;

public interface BuildModel extends Serializable {
  String asJson();

  class Impl implements BuildModel {
    private final String json;

    public Impl(String json) {
      this.json = json;
    }

    @Override
    public String asJson() {
      return json;
    }
  }
}
