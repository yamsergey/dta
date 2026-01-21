package io.yamsergey.dta.tools.sugar;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import lombok.Builder;

@Builder
public record Success<T>(
    @Nonnull T value,
    @Nullable String description) implements Result<T> {

}
