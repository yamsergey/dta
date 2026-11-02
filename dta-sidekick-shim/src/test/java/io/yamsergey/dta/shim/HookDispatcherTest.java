package io.yamsergey.dta.shim;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class HookDispatcherTest {

    private static final String LABEL = HookRegistry.label("a.B", "m", "(I)Ljava/lang/String;");

    @AfterEach
    void reset() {
        HookRegistry.clear();
    }

    @Test
    void labelMatchesSlicerMethodLabelFormat() {
        assertEquals("java.net.URL->openConnection()Ljava/net/URLConnection;",
                HookRegistry.label("java.net.URL", "openConnection", "()Ljava/net/URLConnection;"));
    }

    @Test
    void entryHandlerReceivesThisAndParams() {
        final List<Object> seen = new ArrayList<>();
        HookRegistry.entry(LABEL, new EntryHandler() {
            @Override
            public void onEntry(Object self, Object[] params) {
                seen.add(self);
                seen.add(params);
            }
        });
        Object self = new Object();
        HookDispatcher.onEntry(new Object[] {LABEL, self, 42});
        assertSame(self, seen.get(0));
        assertArrayEquals(new Object[] {42}, (Object[]) seen.get(1));
    }

    @Test
    void staticMethodEntryPassesNullThis() {
        final Object[] seenSelf = {"unset"};
        HookRegistry.entry(LABEL, new EntryHandler() {
            @Override
            public void onEntry(Object self, Object[] params) {
                seenSelf[0] = self;
            }
        });
        HookDispatcher.onEntry(new Object[] {LABEL, null, 1});
        assertNull(seenSelf[0]);
    }

    @Test
    void exitHandlerReplacesResult() {
        HookRegistry.exit(LABEL, new ExitHandler() {
            @Override
            public Object onExit(Object result) {
                return result + "!";
            }
        });
        assertEquals("x!", HookDispatcher.onExit(LABEL, (Object) "x"));
    }

    @Test
    void unknownLabelPassesResultThrough() {
        Object result = new Object();
        assertSame(result, HookDispatcher.onExit("nope->x()Ljava/lang/Object;", result));
        HookDispatcher.onEntry(new Object[] {"nope->x()V", null});
        HookDispatcher.onExit("nope->x()V");
    }

    @Test
    void throwingHandlersAreContainedAndCounted() {
        HookRegistry.entry(LABEL, new EntryHandler() {
            @Override
            public void onEntry(Object self, Object[] params) {
                throw new IllegalStateException("boom");
            }
        });
        HookRegistry.exit(LABEL, new ExitHandler() {
            @Override
            public Object onExit(Object result) {
                throw new IllegalStateException("boom");
            }
        });
        HookDispatcher.onEntry(new Object[] {LABEL, null, 1});
        assertEquals("r", HookDispatcher.onExit(LABEL, (Object) "r"));
        assertEquals(2, HookRegistry.errorCount(LABEL));
        assertTrue(HookRegistry.lastError(LABEL) instanceof IllegalStateException);
    }

    @Test
    void primitiveExitIsVisibleAndReplaceableWithSameBoxType() {
        final Object[] seen = {null};
        String intLabel = HookRegistry.label("a.B", "n", "()I");
        HookRegistry.exit(intLabel, new ExitHandler() {
            @Override
            public Object onExit(Object result) {
                seen[0] = result;
                return 7;
            }
        });
        assertEquals(7, HookDispatcher.onExit(intLabel, 3));
        assertEquals(3, seen[0]);
    }

    @Test
    void primitiveExitWithWrongBoxTypeKeepsOriginal() {
        String longLabel = HookRegistry.label("a.B", "n", "()J");
        HookRegistry.exit(longLabel, new ExitHandler() {
            @Override
            public Object onExit(Object result) {
                return "not a long";
            }
        });
        assertEquals(5L, HookDispatcher.onExit(longLabel, 5L));
        assertEquals(1, HookRegistry.errorCount(longLabel));
    }

    @Test
    void handlerCallingAHookedMethodDoesNotReenter() {
        final int[] calls = {0};
        HookRegistry.entry(LABEL, new EntryHandler() {
            @Override
            public void onEntry(Object self, Object[] params) {
                calls[0]++;
                HookDispatcher.onEntry(new Object[] {LABEL, null, 2});
            }
        });
        HookDispatcher.onEntry(new Object[] {LABEL, null, 1});
        assertEquals(1, calls[0]);
    }

    @Test
    void severalHandlersOnOneMethodChainInRegistrationOrder() {
        final List<String> order = new ArrayList<>();
        HookRegistry.entry(LABEL, new EntryHandler() {
            @Override
            public void onEntry(Object self, Object[] params) {
                order.add("e1");
            }
        });
        HookRegistry.entry(LABEL, new EntryHandler() {
            @Override
            public void onEntry(Object self, Object[] params) {
                order.add("e2");
            }
        });
        HookRegistry.exit(LABEL, new ExitHandler() {
            @Override
            public Object onExit(Object result) {
                return result + "1";
            }
        });
        HookRegistry.exit(LABEL, new ExitHandler() {
            @Override
            public Object onExit(Object result) {
                return result + "2";
            }
        });
        HookDispatcher.onEntry(new Object[] {LABEL, null, 1});
        assertEquals(List.of("e1", "e2"), order);
        assertEquals("x12", HookDispatcher.onExit(LABEL, (Object) "x"));
    }

    @Test
    void oneFailingHandlerDoesNotStopTheOthers() {
        HookRegistry.exit(LABEL, new ExitHandler() {
            @Override
            public Object onExit(Object result) {
                throw new IllegalStateException("boom");
            }
        });
        HookRegistry.exit(LABEL, new ExitHandler() {
            @Override
            public Object onExit(Object result) {
                return result + "!";
            }
        });
        assertEquals("x!", HookDispatcher.onExit(LABEL, (Object) "x"));
        assertEquals(1, HookRegistry.errorCount(LABEL));
    }

    @Test
    void voidExitNotifiesWithNull() {
        final Object[] seen = {"unset"};
        String voidLabel = HookRegistry.label("a.B", "v", "()V");
        HookRegistry.exit(voidLabel, new ExitHandler() {
            @Override
            public Object onExit(Object result) {
                seen[0] = result;
                return null;
            }
        });
        HookDispatcher.onExit(voidLabel);
        assertNull(seen[0]);
    }

    // ---- control entry hook (feature 005) ----

    @Test
    void controlWithoutHandlerProceeds() {
        assertSame(HookDispatcher.PROCEED, HookDispatcher.onEntryControl(new Object[] {LABEL, null, "a"}));
    }

    @Test
    void controlHandlerRewritesArgumentsInPlace() {
        HookRegistry.control(LABEL, new ControlHandler() {
            @Override
            public Object onEntry(Object self, Object[] params) {
                params[0] = "b";
                return HookDispatcher.PROCEED;
            }
        });
        Object[] args = {LABEL, null, "a"};
        assertSame(HookDispatcher.PROCEED, HookDispatcher.onEntryControl(args));
        assertEquals("b", args[2], "the rewritten parameter is visible to the transformed method");
    }

    @Test
    void controlHandlerCanReturnEarly() {
        HookRegistry.control(LABEL, new ControlHandler() {
            @Override
            public Object onEntry(Object self, Object[] params) {
                return "early";
            }
        });
        assertEquals("early", HookDispatcher.onEntryControl(new Object[] {LABEL, null, "a"}));
    }

    @Test
    void throwingControlHandlerProceedsWithArgumentsUntouched() {
        HookRegistry.control(LABEL, new ControlHandler() {
            @Override
            public Object onEntry(Object self, Object[] params) {
                params[0] = "changed";
                throw new IllegalStateException("boom");
            }
        });
        Object[] args = {LABEL, null, "a"};
        assertSame(HookDispatcher.PROCEED, HookDispatcher.onEntryControl(args));
        assertEquals("a", args[2]);
        assertEquals(1, HookRegistry.errorCount(LABEL));
    }

    @Test
    void firstNonProceedResultWins() {
        HookRegistry.control(LABEL, new ControlHandler() {
            @Override
            public Object onEntry(Object self, Object[] params) {
                return HookDispatcher.PROCEED;
            }
        });
        HookRegistry.control(LABEL, new ControlHandler() {
            @Override
            public Object onEntry(Object self, Object[] params) {
                return "second";
            }
        });
        HookRegistry.control(LABEL, new ControlHandler() {
            @Override
            public Object onEntry(Object self, Object[] params) {
                return "third";
            }
        });
        assertEquals("second", HookDispatcher.onEntryControl(new Object[] {LABEL, null, "a"}));
    }

    @Test
    void controlIsNotReentered() {
        final int[] calls = {0};
        HookRegistry.control(LABEL, new ControlHandler() {
            @Override
            public Object onEntry(Object self, Object[] params) {
                calls[0]++;
                return HookDispatcher.onEntryControl(new Object[] {LABEL, null, "x"});
            }
        });
        assertSame(HookDispatcher.PROCEED, HookDispatcher.onEntryControl(new Object[] {LABEL, null, "a"}));
        assertEquals(1, calls[0]);
    }

    /** Feature 020: a message DTA hands to the app from inside a hook lets the app's own calls be hooked. */
    @Test
    void unguardedLetsAHandlerReachHooksAndRestoresTheGuard() {
        final String other = HookRegistry.label("a.B", "send", "(Ljava/lang/String;)Z");
        final int[] sends = {0};
        HookRegistry.entry(other, new EntryHandler() {
            @Override
            public void onEntry(Object self, Object[] params) {
                sends[0]++;
            }
        });
        final int[] calls = {0};
        HookRegistry.entry(LABEL, new EntryHandler() {
            @Override
            public void onEntry(Object self, Object[] params) {
                calls[0]++;
                HookDispatcher.onEntry(new Object[] {other, null, "guarded"});
                HookDispatcher.unguarded(new Runnable() {
                    @Override
                    public void run() {
                        HookDispatcher.onEntry(new Object[] {other, null, "unguarded"});
                    }
                });
                HookDispatcher.onEntry(new Object[] {other, null, "guarded again"});
            }
        });
        HookDispatcher.onEntry(new Object[] {LABEL, null, 1});
        assertEquals(1, calls[0]);
        assertEquals(1, sends[0], "only the unguarded call was dispatched");
    }
}
