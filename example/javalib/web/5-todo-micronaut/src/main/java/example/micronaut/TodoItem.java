package example.micronaut;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.GeneratedValue;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;

@MappedEntity
public record TodoItem(@Nullable @Id @GeneratedValue Long id,
                       String title,
                       boolean completed) {
}
