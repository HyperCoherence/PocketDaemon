---
description: Query a local data API when the phone is on a trusted network.
fetch: https://example.local/api/items
network: 192.168.1.*
auto: false
---

# Local Data

Use this skill as a template for private local integrations. Replace the
`fetch` URL and the instructions below with your own data source and usage
rules.

When the skill is loaded, the app fetches the URL and exposes the response to
the agent as live data. Keep private endpoints on trusted networks, prefer
HTTPS, and avoid including credentials directly in the skill file.
