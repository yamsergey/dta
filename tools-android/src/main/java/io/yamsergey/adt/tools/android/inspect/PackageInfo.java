package io.yamsergey.adt.tools.android.inspect;

import lombok.Builder;
import lombok.Getter;

/**
 * Information about an installed Android package.
 */
@Getter
@Builder
public class PackageInfo {
    private final String packageName;
    private final boolean debuggable;
    private final String versionName;
    private final Integer versionCode;
    private final String installLocation;
}
