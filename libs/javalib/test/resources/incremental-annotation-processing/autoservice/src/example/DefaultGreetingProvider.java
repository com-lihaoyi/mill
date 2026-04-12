package example;

import com.google.auto.service.AutoService;

@AutoService(GreetingProvider.class)
public class DefaultGreetingProvider implements GreetingProvider {}
