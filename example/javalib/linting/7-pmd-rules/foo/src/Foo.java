import org.slf4j.MDC;

public class Foo {
    public void bar() {
        MDC.put(request, "someValue");
        try {
            // ... business logic
        } finally {
            MDC.remove(request);
            // or MDC.clear();
        }
    }
}
