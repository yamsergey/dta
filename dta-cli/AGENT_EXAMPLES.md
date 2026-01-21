# Coding Agent Examples

This document provides examples of how coding agents and automation tools can use the `adt-cli` tool to inspect and interact with Android applications.

## UI Layout Inspection

### Example 1: Find all clickable buttons

```bash
# Get layout as JSON
adt-cli inspect layout --format json | \
  jq '[.. | select(.className? == "android.widget.Button" and .properties?.clickable == true)]'
```

### Example 2: Find element by text

```bash
# Find "Login" button
adt-cli inspect layout --format json | \
  jq '.. | select(.text? == "Login")'
```

### Example 3: Get all text fields

```bash
# Find all EditText elements
adt-cli inspect layout --format json | \
  jq '[.. | select(.className? | contains("EditText"))]'
```

### Example 4: Find element by resource ID

```bash
# Find element with specific ID
adt-cli inspect layout --format json | \
  jq '.. | select(.resourceId? == "com.example.app:id/username")'
```

### Example 5: Get element coordinates for automation

```bash
# Get center coordinates of "Submit" button
adt-cli inspect layout --format json | \
  jq '.. | select(.text? == "Submit") | .bounds | "\(.centerX),\(.centerY)"'
```

## Python Integration

```python
import subprocess
import json

def get_layout_hierarchy():
    """Get the current UI layout as a Python dict."""
    result = subprocess.run(
        ['adt-cli', 'inspect', 'layout', '--format', 'json'],
        capture_output=True,
        text=True
    )
    return json.loads(result.stdout)

def find_element_by_text(text):
    """Find an element by its text content."""
    def search(node):
        if node.get('text') == text:
            return node
        for child in node.get('children', []):
            result = search(child)
            if result:
                return result
        return None
    
    hierarchy = get_layout_hierarchy()
    return search(hierarchy)

def find_clickable_elements():
    """Find all clickable elements."""
    def collect(node, results):
        if node.get('properties', {}).get('clickable'):
            results.append(node)
        for child in node.get('children', []):
            collect(child, results)
    
    hierarchy = get_layout_hierarchy()
    results = []
    collect(hierarchy, results)
    return results

# Example usage
if __name__ == '__main__':
    # Find the login button
    login_btn = find_element_by_text('Login')
    if login_btn:
        bounds = login_btn['bounds']
        print(f"Login button at: ({bounds['centerX']}, {bounds['centerY']})")
    
    # List all clickable elements
    clickable = find_clickable_elements()
    print(f"Found {len(clickable)} clickable elements")
    for elem in clickable:
        print(f"  - {elem['className']}: {elem.get('text', elem.get('contentDesc', 'no text'))}")
```

## JavaScript/Node.js Integration

```javascript
const { execSync } = require('child_process');

function getLayoutHierarchy() {
  const output = execSync('adt-cli inspect layout --format json', { encoding: 'utf-8' });
  return JSON.parse(output);
}

function findElementByText(text) {
  function search(node) {
    if (node.text === text) return node;
    for (const child of node.children || []) {
      const result = search(child);
      if (result) return result;
    }
    return null;
  }
  
  const hierarchy = getLayoutHierarchy();
  return search(hierarchy);
}

function findElementsByClassName(className) {
  const results = [];
  
  function collect(node) {
    if (node.className === className) {
      results.push(node);
    }
    for (const child of node.children || []) {
      collect(child);
    }
  }
  
  const hierarchy = getLayoutHierarchy();
  collect(hierarchy);
  return results;
}

// Example usage
const submitBtn = findElementByText('Submit');
if (submitBtn) {
  console.log(`Submit button: ${submitBtn.resourceId}`);
  console.log(`Position: (${submitBtn.bounds.centerX}, ${submitBtn.bounds.centerY})`);
}

const buttons = findElementsByClassName('android.widget.Button');
console.log(`Found ${buttons.length} buttons`);
```

## AI Agent Prompts

### Example prompt for Claude or GPT

```
I need to automate testing of an Android app. Here's the current UI layout:

[paste output of: adt-cli inspect layout --format json]

Please write a test script that:
1. Finds the username field
2. Finds the password field
3. Finds the login button
4. Returns the coordinates for each element

The script should use the JSON structure above to locate elements by their resourceId or text.
```

### Example prompt for finding elements

```
Given this Android UI hierarchy in JSON format:

[paste JSON]

Find all elements that:
- Are clickable
- Have visible text
- Are within the bounds (0, 500) to (1080, 1500)

Return the results as a list with element type, text, and center coordinates.
```

## Shell Scripting

### Find and tap an element (with ADB)

```bash
#!/bin/bash

# Find element by text and tap it
ELEMENT=$(adt-cli inspect layout --format json | jq -r '.. | select(.text? == "Login") | .bounds | "\(.centerX) \(.centerY)"')

if [ -n "$ELEMENT" ]; then
    adb shell input tap $ELEMENT
    echo "Tapped Login button at: $ELEMENT"
else
    echo "Login button not found"
fi
```

### Verify element exists

```bash
#!/bin/bash

# Check if element with specific ID exists
EXISTS=$(adt-cli inspect layout --format json | \
  jq -e '.. | select(.resourceId? == "com.example.app:id/submit")' > /dev/null && echo "yes" || echo "no")

if [ "$EXISTS" == "yes" ]; then
    echo "Submit button found"
    exit 0
else
    echo "Submit button not found"
    exit 1
fi
```

## Benefits for Agents

1. **No XML Parsing**: JSON is natively supported by all modern languages
2. **Type Safety**: Structured data with consistent field types
3. **Computed Values**: Width, height, centerX, centerY automatically calculated
4. **Easy Filtering**: Use tools like `jq` or native language features
5. **Composable**: Pipe output to other tools for complex queries
6. **Fast**: Direct stdout output for real-time processing

## Tips

- Use `--pretty` flag (default) for human-readable output
- Pipe to `jq` for powerful JSON querying
- Save to file with `-o` for repeated analysis
- Use `--compressed` for faster dumps on complex UIs
- Combine with `adb` commands for full automation

## Combined Screenshot + Layout Analysis

### Python: Visual + Structural Analysis

```python
def analyze_screen():
    """Capture and analyze both visual and structural data."""
    import subprocess
    import json
    from datetime import datetime
    
    timestamp = datetime.now().strftime('%Y%m%d-%H%M%S')
    
    # Capture screenshot
    screenshot = f'screen-{timestamp}.png'
    subprocess.run(['adt-cli', 'inspect', 'screenshot', '-o', screenshot])
    
    # Capture layout
    layout_json = subprocess.check_output(
        ['adt-cli', 'inspect', 'layout', '--format', 'json'],
        text=True
    )
    layout = json.loads(layout_json)
    
    # Analyze
    clickable_elements = []
    def find_clickable(node):
        if node.get('properties', {}).get('clickable'):
            clickable_elements.append({
                'text': node.get('text', ''),
                'id': node.get('resourceId', ''),
                'bounds': node.get('bounds', {})
            })
        for child in node.get('children', []):
            find_clickable(child)
    
    find_clickable(layout)
    
    return {
        'screenshot': screenshot,
        'layout': layout,
        'clickable_count': len(clickable_elements),
        'clickable_elements': clickable_elements
    }

# Usage
result = analyze_screen()
print(f"Screenshot: {result['screenshot']}")
print(f"Found {result['clickable_count']} clickable elements")
```

### Shell: Automated Testing Workflow

```bash
#!/bin/bash
# Complete test automation workflow

TIMESTAMP=$(date +%Y%m%d-%H%M%S)
TEST_DIR="test-results/${TIMESTAMP}"
mkdir -p "${TEST_DIR}"

# Capture initial state
echo "Capturing initial state..."
adt-cli inspect screenshot -o "${TEST_DIR}/01-initial.png"
adt-cli inspect layout --format json -o "${TEST_DIR}/01-initial.json"

# Find and tap login button
echo "Finding login button..."
LOGIN_COORDS=$(adt-cli inspect layout --format json | \
  jq -r '.. | select(.text? == "Login") | .bounds | "\(.centerX) \(.centerY)"')

if [ -n "$LOGIN_COORDS" ]; then
    echo "Tapping login button at: $LOGIN_COORDS"
    adb shell input tap $LOGIN_COORDS
    sleep 2
    
    # Capture after tap
    adt-cli inspect screenshot -o "${TEST_DIR}/02-after-login-tap.png"
    adt-cli inspect layout --format json -o "${TEST_DIR}/02-after-login-tap.json"
fi

echo "Test results saved to: ${TEST_DIR}"
```

## AI Agent Integration

### Example: Claude/GPT Prompt for UI Testing

```
I need to test an Android app. Here's the current state:

Screenshot: [attach screenshot.png]
Layout: [paste JSON from: adt-cli inspect layout --format json]

Please:
1. Identify all interactive elements (buttons, text fields)
2. Suggest a test scenario for the login flow
3. Provide the exact coordinates for automation
4. Identify any accessibility issues

Format your response as a JSON test plan.
```

### Example: Automated Bug Reporting

```python
def create_bug_report(issue_description):
    """Create a comprehensive bug report with screenshot and layout."""
    import subprocess
    import json
    from datetime import datetime
    
    timestamp = datetime.now().strftime('%Y%m%d-%H%M%S')
    report_dir = f'bug-reports/bug-{timestamp}'
    os.makedirs(report_dir, exist_ok=True)
    
    # Capture evidence
    screenshot = f'{report_dir}/screenshot.png'
    layout_file = f'{report_dir}/layout.json'
    
    subprocess.run(['adt-cli', 'inspect', 'screenshot', '-o', screenshot])
    
    layout_output = subprocess.check_output(
        ['adt-cli', 'inspect', 'layout', '--format', 'json'],
        text=True
    )
    
    with open(layout_file, 'w') as f:
        f.write(layout_output)
    
    # Create report
    report = {
        'timestamp': timestamp,
        'description': issue_description,
        'screenshot': screenshot,
        'layout': layout_file,
        'device_info': get_device_info(),  # Custom function
        'app_version': get_app_version()   # Custom function
    }
    
    with open(f'{report_dir}/report.json', 'w') as f:
        json.dump(report, f, indent=2)
    
    print(f"Bug report created: {report_dir}")
    return report_dir
```

## Tips for Screenshot + Layout Workflows

1. **Always capture both**: Screenshots provide visual context, layout provides structure
2. **Use timestamps**: Keep captures organized and traceable
3. **Automate comparisons**: Use image diff tools to detect visual regressions
4. **Store metadata**: Include device info, app version, test scenario
5. **Combine with ADB**: Use layout data to drive UI automation via ADB input commands


## Logcat Analysis

### Example 1: Capture and analyze errors

```bash
# Capture only errors
adt-cli inspect logcat --priority E --lines 100 -o errors.txt

# Analyze with grep
adt-cli inspect logcat --priority E | grep -i "exception\|error\|crash"
```

### Example 2: Monitor specific component

```bash
# Capture logs from ActivityManager only
adt-cli inspect logcat --tag "ActivityManager:I *:S" --lines 500 -o activity.txt

# Monitor app-specific logs
adt-cli inspect logcat --tag "MyApp:D *:S" -o myapp.txt
```

### Example 3: Fresh log capture

```bash
# Clear logs, perform action, capture new logs
adt-cli inspect logcat --clear
# ... perform test action ...
sleep 2
adt-cli inspect logcat --lines 100 -o test-logs.txt
```

### Python: Automated log analysis

```python
import subprocess
import re

def capture_errors():
    """Capture and analyze error logs."""
    result = subprocess.run(
        ['adt-cli', 'inspect', 'logcat', '--priority', 'E', '--lines', '500'],
        capture_output=True,
        text=True
    )
    
    logs = result.stdout
    
    # Parse errors
    errors = []
    for line in logs.split('\n'):
        if 'E/' in line or 'F/' in line:
            errors.append(line)
    
    # Find exceptions
    exceptions = []
    for line in logs.split('\n'):
        if 'Exception' in line or 'Error' in line:
            exceptions.append(line)
    
    return {
        'total_errors': len(errors),
        'exceptions': exceptions,
        'raw_logs': logs
    }

def monitor_app_logs(package_name, duration_seconds=10):
    """Monitor logs for specific app."""
    import time
    
    # Clear logs
    subprocess.run(['adt-cli', 'inspect', 'logcat', '--clear'])
    
    # Wait for activity
    time.sleep(duration_seconds)
    
    # Capture logs
    result = subprocess.run(
        ['adt-cli', 'inspect', 'logcat', '--lines', '1000'],
        capture_output=True,
        text=True
    )
    
    # Filter by package
    app_logs = [line for line in result.stdout.split('\n') 
                if package_name in line]
    
    return app_logs

# Usage
errors = capture_errors()
print(f"Found {errors['total_errors']} errors")
print(f"Found {len(errors['exceptions'])} exceptions")

app_logs = monitor_app_logs('com.example.myapp', duration_seconds=5)
print(f"Captured {len(app_logs)} app-specific log lines")
```

### Shell: Complete diagnostic capture

```bash
#!/bin/bash
# Comprehensive diagnostic capture

TIMESTAMP=$(date +%Y%m%d-%H%M%S)
DIAG_DIR="diagnostics/${TIMESTAMP}"
mkdir -p "${DIAG_DIR}"

echo "Capturing diagnostics..."

# Capture screenshot
adt-cli inspect screenshot -o "${DIAG_DIR}/screenshot.png"

# Capture layout
adt-cli inspect layout --format json -o "${DIAG_DIR}/layout.json"

# Capture all logs
adt-cli inspect logcat --lines 5000 -o "${DIAG_DIR}/logcat-all.txt"

# Capture errors only
adt-cli inspect logcat --priority E --lines 1000 -o "${DIAG_DIR}/logcat-errors.txt"

# Capture warnings and above
adt-cli inspect logcat --priority W --lines 2000 -o "${DIAG_DIR}/logcat-warnings.txt"

# Count errors
ERROR_COUNT=$(grep -c " E/" "${DIAG_DIR}/logcat-errors.txt" || echo "0")
WARNING_COUNT=$(grep -c " W/" "${DIAG_DIR}/logcat-warnings.txt" || echo "0")

echo "Diagnostics saved to: ${DIAG_DIR}"
echo "Errors: ${ERROR_COUNT}"
echo "Warnings: ${WARNING_COUNT}"

# Create summary
cat > "${DIAG_DIR}/summary.txt" << SUMMARY
Diagnostic Capture Summary
==========================
Timestamp: ${TIMESTAMP}
Errors: ${ERROR_COUNT}
Warnings: ${WARNING_COUNT}

Files:
- screenshot.png: Visual state
- layout.json: UI structure
- logcat-all.txt: All logs (last 5000 lines)
- logcat-errors.txt: Errors only (last 1000 lines)
- logcat-warnings.txt: Warnings and above (last 2000 lines)
SUMMARY

cat "${DIAG_DIR}/summary.txt"
```

### JavaScript: Real-time log monitoring

```javascript
const { spawn } = require('child_process');
const fs = require('fs');

function monitorLogs(options = {}) {
  const {
    priority = 'I',
    onLog = (line) => console.log(line),
    outputFile = null
  } = options;
  
  // Clear logs first
  const { execSync } = require('child_process');
  execSync('adt-cli inspect logcat --clear');
  
  // Start monitoring
  const logcat = spawn('adt-cli', [
    'inspect', 'logcat',
    '--priority', priority
  ]);
  
  let buffer = '';
  
  logcat.stdout.on('data', (data) => {
    buffer += data.toString();
    const lines = buffer.split('\n');
    buffer = lines.pop(); // Keep incomplete line
    
    lines.forEach(line => {
      if (line.trim()) {
        onLog(line);
        if (outputFile) {
          fs.appendFileSync(outputFile, line + '\n');
        }
      }
    });
  });
  
  return {
    stop: () => logcat.kill()
  };
}

// Usage
const monitor = monitorLogs({
  priority: 'W',
  onLog: (line) => {
    if (line.includes('Exception') || line.includes('Error')) {
      console.error('ERROR:', line);
    }
  },
  outputFile: 'monitoring.log'
});

// Stop after 30 seconds
setTimeout(() => {
  monitor.stop();
  console.log('Monitoring stopped');
}, 30000);
```

## Complete Inspection Workflow

### Comprehensive test capture

```bash
#!/bin/bash
# Complete inspection workflow for bug reporting

TEST_NAME="$1"
if [ -z "$TEST_NAME" ]; then
    echo "Usage: $0 <test-name>"
    exit 1
fi

TIMESTAMP=$(date +%Y%m%d-%H%M%S)
REPORT_DIR="test-reports/${TEST_NAME}-${TIMESTAMP}"
mkdir -p "${REPORT_DIR}"

echo "Starting comprehensive inspection for: ${TEST_NAME}"

# 1. Clear logs
echo "Clearing logs..."
adt-cli inspect logcat --clear

# 2. Capture initial state
echo "Capturing initial state..."
adt-cli inspect screenshot -o "${REPORT_DIR}/01-initial-screen.png"
adt-cli inspect layout --format json -o "${REPORT_DIR}/01-initial-layout.json"

# 3. Perform test action (placeholder - add your test steps)
echo "Performing test action..."
# ... your test steps here ...
sleep 2

# 4. Capture final state
echo "Capturing final state..."
adt-cli inspect screenshot -o "${REPORT_DIR}/02-final-screen.png"
adt-cli inspect layout --format json -o "${REPORT_DIR}/02-final-layout.json"

# 5. Capture logs
echo "Capturing logs..."
adt-cli inspect logcat --lines 2000 -o "${REPORT_DIR}/logcat-all.txt"
adt-cli inspect logcat --priority W --lines 500 -o "${REPORT_DIR}/logcat-warnings.txt"
adt-cli inspect logcat --priority E --lines 200 -o "${REPORT_DIR}/logcat-errors.txt"

# 6. Analyze
ERROR_COUNT=$(grep -c " E/" "${REPORT_DIR}/logcat-errors.txt" 2>/dev/null || echo "0")
WARNING_COUNT=$(grep -c " W/" "${REPORT_DIR}/logcat-warnings.txt" 2>/dev/null || echo "0")

# 7. Create report
cat > "${REPORT_DIR}/REPORT.md" << REPORT
# Test Report: ${TEST_NAME}

**Timestamp:** ${TIMESTAMP}  
**Status:** $([ "$ERROR_COUNT" -eq 0 ] && echo "✅ PASS" || echo "❌ FAIL")

## Summary
- Errors: ${ERROR_COUNT}
- Warnings: ${WARNING_COUNT}

## Artifacts
- \`01-initial-screen.png\` - Initial screenshot
- \`01-initial-layout.json\` - Initial UI layout
- \`02-final-screen.png\` - Final screenshot
- \`02-final-layout.json\` - Final UI layout
- \`logcat-all.txt\` - Complete logs (2000 lines)
- \`logcat-warnings.txt\` - Warnings and above (500 lines)
- \`logcat-errors.txt\` - Errors only (200 lines)

## Next Steps
$([ "$ERROR_COUNT" -gt 0 ] && echo "- Review errors in logcat-errors.txt" || echo "- No errors found")
$([ "$WARNING_COUNT" -gt 0 ] && echo "- Review warnings in logcat-warnings.txt" || echo "")

REPORT

echo ""
echo "============================================"
echo "Test report created: ${REPORT_DIR}"
echo "Errors: ${ERROR_COUNT}"
echo "Warnings: ${WARNING_COUNT}"
echo "============================================"
echo ""
cat "${REPORT_DIR}/REPORT.md"
```

