package example;

import dagger.Component;

@Component(modules = MessageModule.class)
public interface MessageComponent {
    String message();
}
