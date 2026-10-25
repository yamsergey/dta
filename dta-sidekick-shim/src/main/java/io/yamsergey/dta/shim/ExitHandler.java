package io.yamsergey.dta.shim;

/** Sees a hooked method's return value and may replace it. */
public interface ExitHandler {
    /**
     * @param result the value about to be returned (boxed for primitives, null for void)
     * @return the value to return instead; for primitive methods a value of a different
     *     box type is ignored and counted as an error
     */
    Object onExit(Object result);
}
