package io.yamsergey.dta.sidekick.network;

/**
 * Represents an HTTP header as a name-value pair.
 *
 * <p>This class is used instead of a Map to support multiple headers
 * with the same name (e.g., multiple Set-Cookie headers).</p>
 */
public final class HttpHeader {

    private final String name;
    private final String value;

    public HttpHeader(String name, String value) {
        this.name = name;
        this.value = value;
    }

    public String getName() {
        return name;
    }

    public String getValue() {
        return value;
    }

    @Override
    public String toString() {
        return name + ": " + value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        HttpHeader that = (HttpHeader) o;
        return name.equals(that.name) && value.equals(that.value);
    }

    @Override
    public int hashCode() {
        return 31 * name.hashCode() + value.hashCode();
    }
}
