package example;

import dagger.Module;
import dagger.Provides;

@Module
public class MessageModule {
    @Provides
    static String provideMessage() {
        return "hello";
    }
}
