"""ADB integration for Android device management."""

import asyncio
import re
import subprocess
from dataclasses import dataclass


@dataclass
class Device:
    """Represents a connected Android device."""
    serial: str
    state: str
    model: str | None = None
    product: str | None = None


@dataclass
class SidekickSocket:
    """Represents a sidekick socket discovered on device."""
    socket_name: str
    package_name: str
    device: str


async def run_adb(*args: str, device: str | None = None) -> tuple[int, str, str]:
    """Run an ADB command asynchronously.

    Returns:
        Tuple of (return_code, stdout, stderr)
    """
    cmd = ["adb"]
    if device:
        cmd.extend(["-s", device])
    cmd.extend(args)

    proc = await asyncio.create_subprocess_exec(
        *cmd,
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.PIPE,
    )
    stdout, stderr = await proc.communicate()
    return proc.returncode or 0, stdout.decode(), stderr.decode()


async def list_devices() -> list[Device]:
    """List connected Android devices."""
    code, stdout, _ = await run_adb("devices", "-l")
    if code != 0:
        return []

    devices = []
    for line in stdout.strip().split("\n")[1:]:  # Skip header
        if not line.strip():
            continue
        parts = line.split()
        if len(parts) < 2:
            continue

        serial = parts[0]
        state = parts[1]

        # Parse additional properties
        model = None
        product = None
        for part in parts[2:]:
            if part.startswith("model:"):
                model = part.split(":", 1)[1]
            elif part.startswith("product:"):
                product = part.split(":", 1)[1]

        devices.append(Device(serial=serial, state=state, model=model, product=product))

    return devices


async def list_packages(device: str | None = None, debuggable_only: bool = True) -> list[str]:
    """List installed packages on device.

    Args:
        device: Device serial, or None for default device
        debuggable_only: If True, only return debuggable packages
    """
    args = ["shell", "pm", "list", "packages"]
    if debuggable_only:
        # Use run-as check to filter debuggable packages
        code, stdout, _ = await run_adb("shell", "pm", "list", "packages", device=device)
        if code != 0:
            return []

        packages = []
        for line in stdout.strip().split("\n"):
            if line.startswith("package:"):
                pkg = line.split(":", 1)[1].strip()
                # Check if debuggable by trying run-as
                check_code, _, _ = await run_adb(
                    "shell", "run-as", pkg, "echo", "ok",
                    device=device
                )
                if check_code == 0:
                    packages.append(pkg)
        return packages
    else:
        code, stdout, _ = await run_adb(*args, device=device)
        if code != 0:
            return []
        return [line.split(":", 1)[1].strip() for line in stdout.strip().split("\n") if line.startswith("package:")]


async def discover_sidekick_sockets(device: str | None = None) -> list[SidekickSocket]:
    """Discover sidekick sockets on device.

    Returns list of SidekickSocket for each discovered adt_sidekick_* socket.
    """
    code, stdout, _ = await run_adb(
        "shell", "cat", "/proc/net/unix",
        device=device
    )
    if code != 0:
        return []

    sockets = []
    for line in stdout.split("\n"):
        # Look for adt_sidekick_ sockets in abstract namespace
        match = re.search(r"@adt_sidekick_(\S+)", line)
        if match:
            package_name = match.group(1)
            socket_name = f"adt_sidekick_{package_name}"
            sockets.append(SidekickSocket(
                socket_name=socket_name,
                package_name=package_name,
                device=device or "default"
            ))

    return sockets


async def setup_port_forward(local_port: int, socket_name: str, device: str | None = None) -> bool:
    """Set up ADB port forwarding to a sidekick socket.

    Args:
        local_port: Local TCP port to forward from
        socket_name: Abstract socket name (e.g., adt_sidekick_com.example.app)
        device: Device serial, or None for default

    Returns:
        True if forwarding was set up successfully
    """
    code, _, _ = await run_adb(
        "forward", f"tcp:{local_port}", f"localabstract:{socket_name}",
        device=device
    )
    return code == 0


async def remove_port_forward(local_port: int, device: str | None = None) -> bool:
    """Remove ADB port forwarding.

    Args:
        local_port: Local TCP port that was forwarded
        device: Device serial, or None for default

    Returns:
        True if forward was removed successfully
    """
    code, _, _ = await run_adb(
        "forward", "--remove", f"tcp:{local_port}",
        device=device
    )
    return code == 0


async def list_port_forwards(device: str | None = None) -> dict[int, str]:
    """List current port forwards.

    Returns:
        Dict mapping local port to remote target
    """
    code, stdout, _ = await run_adb("forward", "--list", device=device)
    if code != 0:
        return {}

    forwards = {}
    for line in stdout.strip().split("\n"):
        if not line:
            continue
        parts = line.split()
        if len(parts) >= 3:
            # Format: serial tcp:PORT target
            local = parts[1]
            remote = parts[2]
            if local.startswith("tcp:"):
                port = int(local.split(":")[1])
                forwards[port] = remote

    return forwards


async def capture_screenshot(device: str | None = None) -> bytes | None:
    """Capture a screenshot from the device.

    Returns:
        PNG image bytes, or None on failure
    """
    # Use screencap and pull the result
    code, _, _ = await run_adb(
        "shell", "screencap", "-p", "/data/local/tmp/screenshot.png",
        device=device
    )
    if code != 0:
        return None

    # Pull the file
    proc = await asyncio.create_subprocess_exec(
        "adb", "-s", device, "exec-out", "cat", "/data/local/tmp/screenshot.png",
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.PIPE,
    ) if device else await asyncio.create_subprocess_exec(
        "adb", "exec-out", "cat", "/data/local/tmp/screenshot.png",
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.PIPE,
    )

    stdout, _ = await proc.communicate()

    # Clean up
    await run_adb("shell", "rm", "/data/local/tmp/screenshot.png", device=device)

    return stdout if proc.returncode == 0 else None


async def input_tap(x: int, y: int, device: str | None = None) -> bool:
    """Tap at screen coordinates."""
    code, _, _ = await run_adb("shell", "input", "tap", str(x), str(y), device=device)
    return code == 0


async def input_swipe(x1: int, y1: int, x2: int, y2: int, duration_ms: int = 300, device: str | None = None) -> bool:
    """Swipe from one point to another."""
    code, _, _ = await run_adb(
        "shell", "input", "swipe",
        str(x1), str(y1), str(x2), str(y2), str(duration_ms),
        device=device
    )
    return code == 0


async def input_text(text: str, device: str | None = None) -> bool:
    """Input text (must have focus on a text field)."""
    # Escape special characters
    escaped = text.replace(" ", "%s").replace("'", "\\'").replace('"', '\\"')
    code, _, _ = await run_adb("shell", "input", "text", escaped, device=device)
    return code == 0


async def press_key(keycode: str | int, device: str | None = None) -> bool:
    """Press a key by keycode (e.g., 'KEYCODE_BACK' or 4)."""
    code, _, _ = await run_adb("shell", "input", "keyevent", str(keycode), device=device)
    return code == 0
