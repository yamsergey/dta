#!/bin/bash

# Test script for Issue #41 fix
# Tests multiple applications with sidekick on same emulator

set -e

DEVICE="emulator-5554"
APP1="com.aditya.a.wasnik.league"
APP2="life.league.standalonesdkpresenter"

echo "=========================================="
echo "Testing Issue #41 Fix"
echo "=========================================="
echo ""

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Helper functions
check_pass() {
    echo -e "${GREEN}✓ PASS${NC}: $1"
}

check_fail() {
    echo -e "${RED}✗ FAIL${NC}: $1"
}

check_warn() {
    echo -e "${YELLOW}⚠ WARN${NC}: $1"
}

# Step 1: Clean state
echo "Step 1: Cleaning state..."
adb -s $DEVICE shell "am force-stop $APP1" 2>/dev/null || true
adb -s $DEVICE shell "am force-stop $APP2" 2>/dev/null || true
adb -s $DEVICE forward --remove-all 2>/dev/null || true
sleep 2

SOCKET_COUNT=$(adb -s $DEVICE shell "cat /proc/net/unix | grep dta_sidekick | wc -l" | tr -d ' \r\n')
if [ "$SOCKET_COUNT" -eq "0" ]; then
    check_pass "Clean state achieved (0 sockets)"
else
    check_warn "Found $SOCKET_COUNT stale sockets (will be cleaned by system)"
fi
echo ""

# Step 2: Launch first app
echo "Step 2: Launching first app ($APP1)..."
adb -s $DEVICE shell "am start -n $APP1/.MainActivity" >/dev/null 2>&1 || true
echo "Waiting 5 seconds for initialization..."
sleep 5

SOCKET_COUNT=$(adb -s $DEVICE shell "cat /proc/net/unix | grep 'dta_sidekick_$APP1' | wc -l" | tr -d ' \r\n')
echo "Socket count for $APP1: $SOCKET_COUNT"
if [ "$SOCKET_COUNT" -le "3" ]; then
    check_pass "First app created reasonable number of sockets ($SOCKET_COUNT)"
else
    check_fail "First app created too many sockets ($SOCKET_COUNT, expected ≤3)"
fi
echo ""

# Step 3: Launch second app
echo "Step 3: Launching second app ($APP2)..."
adb -s $DEVICE shell "am start -n $APP2/.MainActivity" >/dev/null 2>&1 || true
echo "Waiting 5 seconds for initialization..."
sleep 5

SOCKET_COUNT=$(adb -s $DEVICE shell "cat /proc/net/unix | grep 'dta_sidekick_$APP2' | wc -l" | tr -d ' \r\n')
echo "Socket count for $APP2: $SOCKET_COUNT"
if [ "$SOCKET_COUNT" -le "3" ]; then
    check_pass "Second app created reasonable number of sockets ($SOCKET_COUNT)"
else
    check_fail "Second app created too many sockets ($SOCKET_COUNT, expected ≤3)"
fi
echo ""

# Step 4: Check total sockets
echo "Step 4: Checking total socket count..."
TOTAL_SOCKETS=$(adb -s $DEVICE shell "cat /proc/net/unix | grep dta_sidekick | wc -l" | tr -d ' \r\n')
echo "Total sidekick sockets: $TOTAL_SOCKETS"
if [ "$TOTAL_SOCKETS" -le "6" ]; then
    check_pass "Total socket count is reasonable ($TOTAL_SOCKETS)"
else
    check_fail "Total socket count is too high ($TOTAL_SOCKETS, expected ≤6)"
fi
echo ""

# Step 5: Test socket connectivity
echo "Step 5: Testing socket connectivity..."

# Set up port forwards
adb -s $DEVICE forward tcp:18640 localabstract:dta_sidekick_$APP1 2>/dev/null
adb -s $DEVICE forward tcp:18641 localabstract:dta_sidekick_$APP2 2>/dev/null

# Test first app
echo "Testing $APP1 on port 18640..."
RESPONSE=$(curl -s http://localhost:18640/health 2>/dev/null || echo "")
if echo "$RESPONSE" | grep -q '"status":"ok"'; then
    check_pass "First app socket is responding"
else
    check_fail "First app socket not responding"
fi

# Test second app
echo "Testing $APP2 on port 18641..."
RESPONSE=$(curl -s http://localhost:18641/health 2>/dev/null || echo "")
if echo "$RESPONSE" | grep -q '"status":"ok"'; then
    check_pass "Second app socket is responding"
else
    check_fail "Second app socket not responding"
fi
echo ""

# Step 6: Summary
echo "=========================================="
echo "Test Summary"
echo "=========================================="
echo "Device: $DEVICE"
echo "App 1: $APP1"
echo "App 2: $APP2"
echo "Total sockets: $TOTAL_SOCKETS"
echo ""

if [ "$TOTAL_SOCKETS" -le "6" ]; then
    echo -e "${GREEN}Overall: PASS${NC}"
    echo "The fix is working correctly!"
else
    echo -e "${RED}Overall: FAIL${NC}"
    echo "The fix may not be applied yet. Please:"
    echo "1. Rebuild apps with updated sidekick library"
    echo "2. Redeploy apps to device"
    echo "3. Run this test again"
fi
echo ""

# Cleanup
echo "Cleaning up port forwards..."
adb -s $DEVICE forward --remove tcp:18640 2>/dev/null || true
adb -s $DEVICE forward --remove tcp:18641 2>/dev/null || true

echo "Done!"
