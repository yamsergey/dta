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

