# Issue #41: Multiple Applications with Sidekick on Same Emulator

## Problem Description

When two applications with sidekick are running on the same emulator (e.g., emulator-5554), there are issues with:
1. Web inspector network and websocket functionality - doesn't show anything
2. Compose tree inspection issues

## Root Cause Analysis

### 1. Duplicate Socket Creation
When examining `/proc/net/unix` on the emulator, we found:
- **50+ duplicate entries** for `@dta_sidekick_life.league.standalonesdkpresenter`
- Only 1 entry for `@dta_sidekick_com.aditya.a.wasnik.league`

This indicates that `LocalServerSocket` was being created multiple times for the same socket name, which causes:
- Resource exhaustion
- Connection confusion
- Potential data mixing between apps

### 2. Missing Thread Safety
The `InspectorServer.start()` method lacked proper synchronization, allowing:
- Multiple concurrent calls to create the same socket
- Race conditions during initialization
- No protection against re-initialization

### 3. Incomplete Cleanup
The `stop()` method didn't properly:
- Shutdown the executor service
- Log cleanup operations
- Handle already-stopped state

## Solution Implemented

### 1. Added Thread-Safe Start Guard
```java
private final AtomicBoolean startCalled = new AtomicBoolean(false);

public void start(String packageName) throws IOException {
    // Prevent multiple concurrent start calls
    if (!startCalled.compareAndSet(false, true)) {
        SidekickLog.w(TAG, "Server start already in progress or completed");
        return;
    }
    // ... rest of implementation
}
```

### 2. Improved Socket Creation with Retry Logic
- Close any existing socket before creating new one
- Retry once if initial creation fails (handles stale sockets from killed apps)
- Reset `startCalled` flag on failure to allow retry

### 3. Enhanced Cleanup in stop()
- Check if server is already stopped
- Properly shutdown executor service
- Added logging for debugging
- Made fields volatile for thread visibility

### 4. Made Fields Volatile
Changed to `volatile` for proper visibility across threads:
- `serverSocket`
- `socketName`
- `packageName`

## Testing

### Before Fix
```bash
$ adb -s emulator-5554 shell "cat /proc/net/unix | grep dta_sidekick"
# 50+ duplicate entries for life.league.standalonesdkpresenter
# 1 entry for com.aditya.a.wasnik.league
```

### Expected After Fix
```bash
$ adb -s emulator-5554 shell "cat /proc/net/unix | grep dta_sidekick"
# 1 entry for life.league.standalonesdkpresenter
# 1 entry for com.aditya.a.wasnik.league
```

## Additional Notes

### Port Forwarding
The web inspector's `SidekickConnectionManager` properly handles multiple apps by:
- Using unique keys: `{device}:{packageName}`
- Allocating sequential ports starting from 18640
- Setting up separate ADB port forwards for each app

### Potential Future Improvements
1. Add metrics/monitoring for socket creation attempts
2. Implement automatic cleanup of stale sockets on startup
3. Add health check endpoint to verify socket is working correctly
4. Consider using a shared socket with multiplexing instead of per-app sockets

## Files Modified
- `dta-sidekick/src/main/java/io/yamsergey/dta/sidekick/server/InspectorServer.java`
