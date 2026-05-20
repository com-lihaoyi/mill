package example;

import org.mapstruct.Mapper;

@Mapper
public interface TruckMapper {
    TruckDto map(Truck truck);
}
