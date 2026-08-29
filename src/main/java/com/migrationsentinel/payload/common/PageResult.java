package com.migrationsentinel.payload.common;

import java.util.List;
import java.util.function.Function;

/** A page of data plus its pagination metadata, passed between service and controller layers. */
public record PageResult<T>(List<T> data, Pagination pagination) {

    public <R> PageResult<R> mapData(Function<List<T>, List<R>> mapper) {
        return new PageResult<>(mapper.apply(this.data), this.pagination);
    }
}
