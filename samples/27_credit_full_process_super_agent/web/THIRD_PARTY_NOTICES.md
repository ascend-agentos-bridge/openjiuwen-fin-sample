# Open-source UI dependencies

The bundled browser application directly imports the following projects. Exact versions and all transitive dependencies are recorded in package-lock.json.

- A2UI Lit renderer (`@a2ui/lit` 0.15.0) and shared protocol processor (`@a2ui/web_core`): Apache-2.0, Google LLC and A2UI contributors. https://github.com/a2ui-project/a2ui
- d3-org-chart 3.1.1: MIT, David B. https://github.com/bumbeishvili/org-chart
- marked 15.0.12: MIT, Christopher Jeffrey and contributors. https://github.com/markedjs/marked
- DOMPurify 3.2.6: Apache-2.0 OR MPL-2.0, Cure53 and contributors. https://github.com/cure53/DOMPurify
- Vite 6.1.0 (build tool): MIT. https://github.com/vitejs/vite

A2UI v0.9 surfaces originate from trusted Java business snapshots. Free-text model replies use Markdown with DOMPurify; they never supply executable HTML or business facts for cards. Organization chart statuses originate from actual backend callback events.
