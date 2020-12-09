package lambdatest;

import static de.tobiasroeser.lambdatest.Expect.*;

import org.testng.annotations.*;
import de.tobiasroeser.lambdatest.testng.FreeSpec;


/**
 * Test case taken from https://github.com/lefou/LambdaTest#writing-tests-with-lambda-test
 */
public class SimpleLambdaTest extends FreeSpec {
  public SimpleLambdaTest() {

    test("1 + 1 = 2", () -> {
      expectEquals(1 + 1, 2);
    });

    test("a pending test", () -> pending());

    test("divide by zero", () -> {
      int a = 2;
      int b = 0;
      intercept(ArithmeticException.class, () -> {
        int c = a / b;
      });
    });

    section("A String should", () -> {
      final String aString = "A string";

      test("match certain criteria", () -> {
        expectString(aString)
          .contains("string")
          .containsIgnoreCase("String")
          .startsWith("A")
          .endsWith("ng")
          .hasLength(8);
      });

      test("be not longer than 2", () -> {
        expectString(aString).isLongerThan(2);
      });
    });

    test("demo of a fail", () -> {
      "yes".equals("yes and no");
    });
  }
}
