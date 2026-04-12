package example;

import com.google.auto.service.AutoService;

@AutoService(GreetingProvider.class)
public class DefaultGreetingProvider implements GreetingProvider {
    @Override
    public String greet() {
        return "hello";
    }
}
