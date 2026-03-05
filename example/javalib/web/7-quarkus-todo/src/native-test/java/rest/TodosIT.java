package rest;

import io.quarkus.test.junit.QuarkusIntegrationTest;

@QuarkusIntegrationTest
public class TodosIT extends TodosTest {
  // This executes the same tests as TodosTest,
  // but against the native binary instead of the JVM.
}
