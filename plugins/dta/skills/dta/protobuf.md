# Protobuf bodies

DTA stores binary bodies byte for byte. It decodes protobuf to JSON when it knows the message type:

- **OTLP** (OpenTelemetry traces, metrics, logs over HTTP) decodes automatically, as the official OTLP/JSON (hex trace and span ids).
- **The app's own protobuf** needs its schema:
  1. `network_schema_add` with `name` and `paths`: `.proto` files or directories on this machine (imports resolve relative to a directory), or one compiled descriptor-set file. No `protoc` needed.
  2. `network_mapping_add`: `urlPattern` (glob over the full URL, `*` = any run of characters), `part` (`request`, `response`, `message`), `schema`, `type` (fully qualified, e.g. `shop.v1.Order`), optionally `method` (HTTP) or `direction` (`out`/`in`, WebSocket).
  3. `network_body` now returns JSON with field names; `decoding` says which schema and type, and any warnings (unknown fields, implausible values).

When decoding fails, `decoding.outcome` is `not-decoded` with the reason; fetch raw bytes with `output`. `bodyContains` searches inside binary bodies without a schema. `network_mapping_list` shows mappings and why one is broken; removing a schema lists the mappings it broke.

Schemas and mappings are kept per app on this machine (`~/.dta/schemas/`) until removed.
