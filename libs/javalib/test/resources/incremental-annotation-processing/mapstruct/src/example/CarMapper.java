package example;

import org.mapstruct.Mapper;

@Mapper
public interface CarMapper {
    CarDto map(Car car);
}
