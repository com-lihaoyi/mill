package example.micronaut;

import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.server.exceptions.ExceptionHandler;
import io.micronaut.http.server.exceptions.response.ErrorContext;
import io.micronaut.http.server.exceptions.response.ErrorResponseProcessor;
import jakarta.inject.Singleton;

@Produces
@Singleton
@Requires(classes = {TodoItemNotFoundException.class, ExceptionHandler.class})
class TodoItemNotFoundExceptionHandler implements ExceptionHandler<TodoItemNotFoundException, HttpResponse> {

    private final ErrorResponseProcessor<?> errorResponseProcessor;

    public TodoItemNotFoundExceptionHandler(ErrorResponseProcessor<?> errorResponseProcessor) {
        this.errorResponseProcessor = errorResponseProcessor;
    }

    @Override
    public HttpResponse handle(HttpRequest request, TodoItemNotFoundException e) {
        return errorResponseProcessor.processResponse(ErrorContext.builder(request)
                .cause(e)
                .errorMessage(e.getMessage())
                .build(), HttpResponse.notFound());
    }
}