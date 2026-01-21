package io.yamsergey.dta.tools.android.model.variant;

import java.io.Serializable;

import lombok.Builder;

@Builder(toBuilder = true)
public record BuildVariant(
    String name,
    String displayName,
    Boolean isDefault) implements Serializable {
}
