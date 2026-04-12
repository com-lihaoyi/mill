package secprov;

import static org.junit.Assert.*;
import org.junit.Test;
import java.security.Provider;
import java.security.Security;

public class SecurityProviderTest {

  /**
   * A minimal security provider for testing classloader isolation.
   */
  public static class TestProvider extends Provider {
    public TestProvider() {
      super("MillTestProvider", "1.0", "Test security provider for classloader isolation testing");
    }
  }

  @Test
  public void testSecurityProviderIsolation() {
    // If withClassLoader properly cleans up security providers, there should be
    // no stale provider from a previous classloader when this test runs again.
    Provider existing = Security.getProvider("MillTestProvider");
    assertNull(
        "Stale security provider found from a previous classloader; "
            + "withClassLoader should clean up providers registered in isolated classloaders",
        existing
    );
    // Register our provider (will be cleaned up by withClassLoader after the test)
    Security.addProvider(new TestProvider());
    assertNotNull(Security.getProvider("MillTestProvider"));
  }
}
