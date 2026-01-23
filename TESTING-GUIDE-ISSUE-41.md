# Testing Guide for Issue #41 Fix

## Prerequisites
1. Two Android apps with sidekick integrated
2. Emulator or device running (e.g., emulator-5554)
3. Web inspector running (`dta-inspector-web`)

## Testing Steps

### 1. Clean State
First, ensure a clean state by killing any existing apps and clearing port forwards:

```bash
# Kill any running apps with sidekick
adb -s emulator-5554 shell "am force-stop com.aditya.a.wasnik.league"
adb -s emulator-5554 shell "am force-stop life.league.standalonesdkpresenter"

# Clear all port forwards
adb -s emulator-5554 forward --remove-all

# Verify no sidekick sockets exist
adb -s emulator-5554 shell "cat /proc/net/unix | grep dta_sidekick"
# Should return empty or very few entries
```

### 2. Launch First App
```bash
# Launch first app
adb -s emulator-5554 shell "am start -n com.aditya.a.wasnik.league/.MainActivity"

# Wait 5 seconds for initialization
sleep 5

# Check socket creation
adb -s emulator-5554 shell "cat /proc/net/unix | grep dta_sidekick | wc -l"
# Expected: 1 line (or very few, not 50+)

# Verify the socket name
adb -s emulator-5554 shell "cat /proc/net/unix | grep dta_sidekick"
# Expected: @dta_sidekick_com.aditya.a.wasnik.league
```

### 3. Launch Second App
```bash
# Launch second app
adb -s emulator-5554 shell "am start -n life.league.standalonesdkpresenter/.MainActivity"

# Wait 5 seconds for initialization
sleep 5

# Check socket creation
adb -s emulator-5554 shell "cat /proc/net/unix | grep dta_sidekick | wc -l"
# Expected: 2 lines (or very few per app, not 50+ for one app)

# Verify both socket names
adb -s emulator-5554 shell "cat /proc/net/unix | grep dta_sidekick"
# Expected:
# @dta_sidekick_com.aditya.a.wasnik.league
# @dta_sidekick_life.league.standalonesdkpresenter
```

### 4. Test Web Inspector Discovery
```bash
# Start web inspector (if not already running)
cd dta-inspector-web
./gradlew bootRun

# In another terminal, test discovery
curl http://localhost:8080/api/devices
# Should show emulator-5554

curl http://localhost:8080/api/apps?device=emulator-5554
# Should show both apps:
# - com.aditya.a.wasnik.league
# - life.league.standalonesdkpresenter
```

### 5. Test Network Inspection for Both Apps

#### First App
```bash
# Get network requests for first app
curl "http://localhost:8080/api/network/requests?package=com.aditya.a.wasnik.league&device=emulator-5554"
# Should return JSON with network requests (if any)

# Get websocket connections for first app
curl "http://localhost:8080/api/websocket/connections?package=com.aditya.a.wasnik.league&device=emulator-5554"
# Should return JSON with websocket connections (if any)
```

#### Second App
```bash
# Get network requests for second app
curl "http://localhost:8080/api/network/requests?package=life.league.standalonesdkpresenter&device=emulator-5554"
# Should return JSON with network requests (if any)

# Get websocket connections for second app
curl "http://localhost:8080/api/websocket/connections?package=life.league.standalonesdkpresenter&device=emulator-5554"
# Should return JSON with websocket connections (if any)
```

### 6. Test Compose Tree for Both Apps
```bash
# Get compose tree for first app
curl "http://localhost:8080/api/compose/tree?package=com.aditya.a.wasnik.league&device=emulator-5554"
# Should return JSON with compose tree

# Get compose tree for second app
curl "http://localhost:8080/api/compose/tree?package=life.league.standalonesdkpresenter&device=emulator-5554"
# Should return JSON with compose tree
```

### 7. Check Port Forwards
```bash
# Verify port forwards are set up correctly
adb -s emulator-5554 forward --list
# Expected: Two port forwards, one for each app
# Example:
# emulator-5554 tcp:18640 localabstract:dta_sidekick_com.aditya.a.wasnik.league
# emulator-5554 tcp:18641 localabstract:dta_sidekick_life.league.standalonesdkpresenter
```

### 8. Test Direct Socket Communication
```bash
# Test first app's socket directly
adb -s emulator-5554 forward tcp:18640 localabstract:dta_sidekick_com.aditya.a.wasnik.league
curl http://localhost:18640/health
# Should return: {"status":"ok","name":"ADT Sidekick",...}

# Test second app's socket directly
adb -s emulator-5554 forward tcp:18641 localabstract:dta_sidekick_life.league.standalonesdkpresenter
curl http://localhost:18641/health
# Should return: {"status":"ok","name":"ADT Sidekick",...}
```

## Expected Results

### ✅ Success Criteria
1. Each app creates exactly **1 socket** (not 50+)
2. Both apps' sockets are discoverable
3. Web inspector can connect to both apps independently
4. Network requests are captured correctly for each app
5. WebSocket connections are captured correctly for each app
6. Compose tree is accessible for both apps
7. No data mixing between apps

### ❌ Failure Indicators
1. Multiple duplicate socket entries for same app
2. Connection timeouts when accessing second app
3. Empty responses for network/websocket data
4. Data from one app appearing in another app's response
5. Compose tree showing wrong app's UI

## Debugging

### Check Logs
```bash
# Check sidekick initialization logs
adb -s emulator-5554 logcat -s "ADT-Sidekick:*" "InspectorServer:*"

# Look for:
# - "Server started on socket: dta_sidekick_{package}"
# - "Server already running" (should only appear once per app)
# - Any error messages about socket creation
```

### Check Socket State
```bash
# Count sockets per app
adb -s emulator-5554 shell "cat /proc/net/unix | grep 'dta_sidekick_com.aditya.a.wasnik.league' | wc -l"
adb -s emulator-5554 shell "cat /proc/net/unix | grep 'dta_sidekick_life.league.standalonesdkpresenter' | wc -l"
# Each should return 1 (or very small number)
```

### Manual Port Forward Test
```bash
# If web inspector isn't working, test manually
adb -s emulator-5554 forward tcp:9999 localabstract:dta_sidekick_life.league.standalonesdkpresenter
curl http://localhost:9999/health
curl http://localhost:9999/network/requests
curl http://localhost:9999/websocket/connections
```

## Cleanup After Testing
```bash
# Stop apps
adb -s emulator-5554 shell "am force-stop com.aditya.a.wasnik.league"
adb -s emulator-5554 shell "am force-stop life.league.standalonesdkpresenter"

# Remove port forwards
adb -s emulator-5554 forward --remove-all

# Verify cleanup
adb -s emulator-5554 shell "cat /proc/net/unix | grep dta_sidekick"
# Should return empty
```
