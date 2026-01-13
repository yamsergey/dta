"""Sidekick REST API client."""

import httpx
from dataclasses import dataclass
from typing import Any


@dataclass
class SidekickConnection:
    """Represents a connection to a sidekick instance."""
    host: str
    port: int
    package_name: str
    device: str

    @property
    def base_url(self) -> str:
        return f"http://{self.host}:{self.port}"


class SidekickClient:
    """Client for communicating with ADT Sidekick REST API."""

    def __init__(self, connection: SidekickConnection):
        self.connection = connection
        self._client = httpx.AsyncClient(base_url=connection.base_url, timeout=30.0)

    async def close(self):
        """Close the HTTP client."""
        await self._client.aclose()

    async def __aenter__(self):
        return self

    async def __aexit__(self, *args):
        await self.close()

    # Health check
    async def health(self) -> dict[str, Any]:
        """Get sidekick health status."""
        resp = await self._client.get("/health")
        resp.raise_for_status()
        return resp.json()

    # Compose UI
    async def compose_tree(self) -> dict[str, Any]:
        """Get Compose UI hierarchy."""
        resp = await self._client.get("/compose/tree")
        resp.raise_for_status()
        return resp.json()

    async def compose_hierarchy(self) -> dict[str, Any]:
        """Get full Compose hierarchy."""
        resp = await self._client.get("/compose/hierarchy")
        resp.raise_for_status()
        return resp.json()

    async def compose_semantics(self) -> dict[str, Any]:
        """Get Compose semantics tree."""
        resp = await self._client.get("/compose/semantics")
        resp.raise_for_status()
        return resp.json()

    async def compose_select(self, x: int, y: int) -> dict[str, Any]:
        """Hit test at coordinates."""
        resp = await self._client.get("/compose/select", params={"x": x, "y": y})
        resp.raise_for_status()
        return resp.json()

    async def compose_select_all(self, x: int, y: int) -> dict[str, Any]:
        """Hit test all layers at coordinates."""
        resp = await self._client.get("/compose/select-all", params={"x": x, "y": y})
        resp.raise_for_status()
        return resp.json()

    async def compose_element(self, element_id: str) -> dict[str, Any]:
        """Get element by ID."""
        resp = await self._client.get(f"/compose/element/{element_id}")
        resp.raise_for_status()
        return resp.json()

    async def compose_screenshot(self) -> bytes:
        """Get screenshot as PNG."""
        resp = await self._client.get("/compose/screenshot")
        resp.raise_for_status()
        return resp.content

    # Network
    async def network_requests(self) -> dict[str, Any]:
        """Get captured network requests."""
        resp = await self._client.get("/network/requests")
        resp.raise_for_status()
        return resp.json()

    async def network_request(self, request_id: str) -> dict[str, Any]:
        """Get network request details."""
        resp = await self._client.get(f"/network/requests/{request_id}")
        resp.raise_for_status()
        return resp.json()

    async def network_stats(self) -> dict[str, Any]:
        """Get network statistics."""
        resp = await self._client.get("/network/stats")
        resp.raise_for_status()
        return resp.json()

    async def network_clear(self) -> dict[str, Any]:
        """Clear captured network requests."""
        resp = await self._client.delete("/network/clear")
        resp.raise_for_status()
        return resp.json()

    # WebSocket
    async def websocket_connections(self) -> dict[str, Any]:
        """Get captured WebSocket connections."""
        resp = await self._client.get("/websocket/connections")
        resp.raise_for_status()
        return resp.json()

    async def websocket_connection(self, connection_id: str) -> dict[str, Any]:
        """Get WebSocket connection details with messages."""
        resp = await self._client.get(f"/websocket/connections/{connection_id}")
        resp.raise_for_status()
        return resp.json()

    async def websocket_clear(self) -> dict[str, Any]:
        """Clear captured WebSocket connections."""
        resp = await self._client.delete("/websocket/clear")
        resp.raise_for_status()
        return resp.json()

    # Selection
    async def get_selected_element(self) -> dict[str, Any]:
        """Get currently selected UI element."""
        resp = await self._client.get("/selection/element")
        resp.raise_for_status()
        return resp.json()

    async def set_selected_element(self, element: dict[str, Any] | None) -> dict[str, Any]:
        """Set currently selected UI element."""
        resp = await self._client.post("/selection/element", json=element)
        resp.raise_for_status()
        return resp.json()

    async def get_selected_network(self) -> dict[str, Any]:
        """Get currently selected network request."""
        resp = await self._client.get("/selection/network")
        resp.raise_for_status()
        return resp.json()

    async def set_selected_network(self, request: dict[str, Any] | None) -> dict[str, Any]:
        """Set currently selected network request."""
        resp = await self._client.post("/selection/network", json=request)
        resp.raise_for_status()
        return resp.json()
