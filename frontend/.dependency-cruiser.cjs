/** @type {import('dependency-cruiser').IConfiguration} */
module.exports = {
  forbidden: [
    { name: "no-circular", severity: "error", from: {}, to: { circular: true } },
    {
      name: "no-orphans",
      severity: "error",
      from: { orphan: true, pathNot: ["\.d\.ts$", "(^|/)vite-env\.d\.ts$"] },
      to: {},
    },
    {
      name: "no-http-client-library",
      comment: "Plain fetch only, wrapped once in src/api/client.ts.",
      severity: "error",
      from: {},
      to: { dependencyTypes: ["npm"], path: "^(axios|ky|got|superagent|node-fetch|@tanstack/react-query|swr)$" },
    },
    {
      name: "no-router-or-ui-library",
      comment: "The lighter stack: no router, UI kit or data library (ADR 0002).",
      severity: "error",
      from: {},
      to: {
        dependencyTypes: ["npm"],
        path: "^(react-router|react-router-dom|@mui/|@chakra-ui/|antd|radix-ui|@radix-ui/|tailwindcss|styled-components|@emotion/)",
      },
    },
    {
      name: "api-is-the-only-network-layer",
      comment:
        "Only the typed api modules (and the app shell, which wires global handlers) may touch the fetch wrapper.",
      severity: "error",
      from: { pathNot: "^src/(api|app)/" },
      to: { path: "^src/api/client\.ts$" },
    },
    {
      name: "components-are-pure",
      comment: "Shared components take props; they never call the API or import screens (API types are allowed).",
      severity: "error",
      from: { path: "^src/components/" },
      to: { path: "^src/(api|screens|app)/", pathNot: "^src/api/problem\.ts$" },
    },
    {
      name: "no-cross-role-screen-imports",
      severity: "error",
      from: { path: "^src/screens/([^/]+)/" },
      to: { path: "^src/screens/([^/]+)/", pathNot: ["^src/screens/$1/", "^src/screens/shared/"] },
    },
    {
      name: "only-the-shell-mounts-screens",
      severity: "error",
      from: { pathNot: "^src/(app|screens)/" },
      to: { path: "^src/screens/" },
    },
    {
      name: "api-has-no-ui",
      severity: "error",
      from: { path: "^src/api/" },
      to: { path: "^src/(screens|components|app)/|^node_modules/react-dom" },
    },
  ],
  options: {
    doNotFollow: { path: "node_modules" },
    tsPreCompilationDeps: true,
    tsConfig: { fileName: "tsconfig.app.json" },
    enhancedResolveOptions: {
      exportsFields: ["exports"],
      conditionNames: ["import", "require", "node", "default", "types"],
    },
    reporterOptions: { text: { highlightFocused: true } },
  },
};
