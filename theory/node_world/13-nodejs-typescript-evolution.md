# Node.js & TypeScript Evolution — Senior Engineer Interview Preparation

---

## 1. Node.js Version History (Key Features)

Node.js ships a new major version every April. Even-numbered releases become LTS in October: **12 months Active LTS** + **18 months Maintenance** (30 months total). Odd-numbered releases never reach LTS.

| Version | Release  | V8     | npm  | Key Theme                        |
|---------|----------|--------|------|----------------------------------|
| Node 10 | Apr 2018 | 6.8    | 6.x  | N-API stable                     |
| Node 12 | Apr 2019 | 7.4    | 6.x  | ES modules experimental          |
| Node 14 | Apr 2020 | 8.1    | 6.x  | Optional chaining, diagnostics   |
| Node 16 | Apr 2021 | 9.0    | 7.x  | Apple Silicon, npm workspaces    |
| Node 18 | Apr 2022 | 10.1   | 8.x  | fetch, test runner, watch mode   |
| Node 20 | Apr 2023 | 11.3   | 9.x  | Stable test runner, permissions  |
| Node 22 | Apr 2024 | 12.4   | 10.x | require(ESM), WebSocket client   |
| Node 24 | Apr 2025 | 13.x   | 11.x | URLPattern, stable type stripping|

### Node 10 — N-API Stable

N-API (Node-API) is the ABI-stable C API for native addons. Before N-API, native modules broke on every major Node upgrade. N-API decoupled addons from V8 internals -- a module compiled on Node 10 works on 12+ without recompilation. Packages like `sharp` and `better-sqlite3` benefited enormously.

### Node 12 — ES Modules (Experimental), Worker Threads Stable

Experimental ESM behind `--experimental-modules`. Stabilized `worker_threads` for true parallelism without child process overhead. Also: private class fields (`#field`), `Array.flat()`, `Object.fromEntries()`.

### Node 14 — Diagnostics and Language Features

V8 8.1 brought optional chaining (`?.`) and nullish coalescing (`??`) natively. Diagnostic reports became stable. `fs/promises` API stabilized. Top-level `await` landed as experimental.

### Node 16 — Apple Silicon, npm 7, AbortController

First release with prebuilt M1 binaries. npm 7 introduced workspaces, automatic peer dependency installation (breaking change from npm 3-6), and `package-lock.json` v2. `AbortController`/`AbortSignal` became globals.

### Node 18 — fetch, Test Runner, Web Streams

Global `fetch()` (via Undici) eliminated `node-fetch` for basic HTTP. Built-in test runner (`node:test`) with `describe`, `it`, `mock`. Watch mode (`node --watch`) reduced `nodemon` dependency. Web Streams API (`ReadableStream`, `WritableStream`, `TransformStream`).

### Node 20 — Stable Test Runner, Permission Model, .env Support

Stable test runner with coverage (`--experimental-test-coverage`). Experimental permission model (`--experimental-permission --allow-fs-read=./data`). Built-in `.env` support: `node --env-file=.env app.js`. Single executable application feature for standalone binaries.

### Node 22 — require(ESM), WebSocket, Glob, Type Stripping

The most requested feature: `require()` for ES modules. Previewed in Node 22.0 behind `--experimental-require-module`; unflagged in Node 22.12 (Dec 2024) and default in Node 23+ — enabling gradual ESM adoption. Built-in WebSocket client (Undici-based). `glob`/`globSync` in `node:fs`. Maglev compiler in V8 12.4. TypeScript type stripping (`--experimental-strip-types`) for direct `.ts` execution.

### Node 24 — What Is Coming

- **URLPattern API**: Built-in URL pattern matching for routing
- **Stable type stripping**: Run `.ts` without flags
- **Stable `require(esm)`**: CJS-ESM bridge out of experimental
- **`node:sqlite`**: Built-in SQLite (experimental)
- **Permission model refinements**: Granular network permissions

---

## 2. TypeScript Version History (Key Features)

TypeScript ships roughly every 3 months. The type system is intentionally unsound in specific areas for pragmatism.

| Version | Release  | Key Feature                                  |
|---------|----------|----------------------------------------------|
| TS 4.0  | Aug 2020 | Variadic tuple types, labeled tuples         |
| TS 4.1  | Nov 2020 | Template literal types, key remapping        |
| TS 4.2  | Feb 2021 | Leading/middle rest in tuples                |
| TS 4.3  | May 2021 | `override` keyword                           |
| TS 4.4  | Aug 2021 | Control flow for aliased conditions          |
| TS 4.5  | Nov 2021 | `Awaited` type, type-only import specifiers  |
| TS 4.7  | May 2022 | ESM support for Node (`node16`)              |
| TS 4.9  | Nov 2022 | `satisfies` operator                         |
| TS 5.0  | Mar 2023 | Decorators (stage 3), `const` type params    |
| TS 5.1  | Jun 2023 | Easier implicit returns for undefined        |
| TS 5.2  | Aug 2023 | `using` declarations                         |
| TS 5.3  | Nov 2023 | Import attributes                            |
| TS 5.4  | Mar 2024 | `NoInfer` utility type                       |
| TS 5.5  | Jun 2024 | Inferred type predicates, `isolatedDeclarations` |
| TS 5.6  | Sep 2024 | Disallowed nullish/truthy checks             |
| TS 5.7  | Nov 2024 | `--module node18`, path rewriting            |
| TS 5.8  | Feb 2025 | Granular return expression checks            |

### TS 4.0-4.1 — Tuple and Template Literal Types

Variadic tuples enable typed function composition over argument lists. Template literal types bring string manipulation into the type system:

```typescript
type Concat<T extends unknown[], U extends unknown[]> = [...T, ...U];
// Concat<[1, 2], [3, 4]> = [1, 2, 3, 4]

type EventName = `on${Capitalize<string>}`; // "onClick", "onHover", etc.

type Getters<T> = {
  [K in keyof T as `get${Capitalize<string & K>}`]: () => T[K];
};
// Getters<{ name: string }> = { getName: () => string }
```

### TS 4.5 — Awaited Type

`Awaited<T>` recursively unwraps promises, fixing `Promise.all()` typing:

```typescript
type A = Awaited<Promise<Promise<number>>>; // number
import { type User, createUser } from './models'; // type-only specifier
```

### TS 4.7 — ESM Support for Node

`"module": "node16"` and `"moduleResolution": "node16"` enabled proper Node ESM. Import specifiers must include extensions. `.mts` emits `.mjs`, `.cts` emits `.cjs`.

```typescript
import { helper } from './utils.js'; // .js extension required even for .ts source
```

### TS 4.9 — satisfies Operator

Validates an expression matches a type without widening the inferred type:

```typescript
type ColorMap = Record<string, string | [number, number, number]>;
const colors = {
  red: [255, 0, 0],
  green: '#00ff00',
} satisfies ColorMap;
colors.green.toUpperCase(); // OK -- TS knows green is string
colors.red[0];              // OK -- TS knows red is tuple
```

### TS 5.0 — Stage 3 Decorators, `const` Type Parameters

Spec-compliant decorators replacing legacy `experimentalDecorators`. `const` type parameters infer literal types:

```typescript
function getNames<const T extends readonly string[]>(names: T): T { return names; }
const b = getNames(['alice', 'bob']); // readonly ["alice", "bob"] (not string[])
```

`--moduleResolution bundler` supports bare specifiers without file extensions.

### TS 5.2 — `using` Declarations

Explicit Resource Management (TC39 proposal) for deterministic cleanup:

```typescript
class Connection implements Disposable {
  [Symbol.dispose]() { this.close(); }
}
function query() {
  using conn = new Connection();
  return conn.query('SELECT 1'); // conn disposed when scope exits
}
```

### TS 5.4 — NoInfer Utility Type

Prevents a position from contributing to inference:

```typescript
function getOrDefault<T>(value: T | undefined, defaultValue: NoInfer<T>): T {
  return value ?? defaultValue;
}
getOrDefault('hello', 42); // Error: number not assignable to string
```

### TS 5.5 — Inferred Type Predicates, isolatedDeclarations

Headline set: **Map.groupBy / Object.groupBy type support; inferred type predicates; `--isolatedDeclarations`.**

Filter now narrows types automatically:

```typescript
const mixed: (string | number)[] = ['hello', 42, 'world'];
const strings = mixed.filter(x => typeof x === 'string'); // string[] in 5.5+
```

`--isolatedDeclarations` enables parallel `.d.ts` generation by tools other than `tsc`.

### TS 5.8+ — What Is Coming

- **TypeScript native initiative**: Potential Go/Rust port of the checker for massive speedups
- **`--erasableSyntaxOnly`**: Aligning with Node.js type stripping
- **Module system simplification**: Reducing the `module`/`moduleResolution` combination matrix
- **TC39 tracking**: Pattern matching, pipeline operator, signals

---

## 3. ESM Migration

### CJS vs ESM

CJS: `require()`/`module.exports`, synchronous, dynamic (runtime), no tree shaking.
ESM: `import`/`export`, asynchronous, static (compile-time), tree shaking enabled.

### `"type": "module"` in package.json

| `"type"` value        | `.js` treated as | `.mjs` | `.cjs` |
|-----------------------|------------------|--------|--------|
| `"module"`            | ESM              | ESM    | CJS    |
| `"commonjs"` (default)| CJS             | ESM    | CJS    |

### __dirname/__filename Replacements

Legacy: `fileURLToPath(import.meta.url)` + `dirname()`. Node 20.11+: `import.meta.dirname` and `import.meta.filename` (preferred).

### Dual Package Publishing

```json
{
  "exports": {
    ".": {
      "import": { "types": "./dist/index.d.mts", "default": "./dist/index.mjs" },
      "require": { "types": "./dist/index.d.cts", "default": "./dist/index.cjs" }
    }
  }
}
```

Key rules: `types` condition must come before `default`. The `"."` entry replaces `"main"` for modern resolvers. Anything not in `exports` is inaccessible (encapsulation).

### Interop: createRequire()

```javascript
import { createRequire } from 'module';
const require = createRequire(import.meta.url);
const pkg = require('./package.json'); // JSON import from ESM
```

### Common Pain Points

| Problem | Solution |
|---------|----------|
| `__dirname` missing | `import.meta.dirname` (Node 20.11+) |
| JSON imports in ESM | Import attributes or `createRequire()` |
| `require.resolve()` | `import.meta.resolve()` (Node 20+) |
| Jest + ESM | `--experimental-vm-modules` or switch to Vitest |
| `ts-node` + ESM | Use `tsx` instead (esbuild-based) |
| Circular deps differ | ESM gets live bindings; CJS gets snapshots |

---

## 4. Package Manager Evolution

### Comparison Table

| Feature                    | npm 10         | Yarn Berry     | pnpm 10        |
|---------------------------|----------------|----------------|----------------|
| Lock file                 | `package-lock.json` | `yarn.lock` | `pnpm-lock.yaml` |
| Cold install speed        | Moderate       | Fast (PnP)     | Fast           |
| Disk usage                | High           | Low (PnP)      | Low (store)    |
| Phantom dep protection    | No             | Yes (PnP)      | Yes (strict)   |
| Workspaces                | Yes            | Yes             | Yes            |
| Dep patching              | `overrides`    | `resolutions`   | `pnpm patch`   |
| Plugin system             | No             | Yes             | No             |
| Bundled with Node         | Yes            | Via Corepack    | Via Corepack   |

### npm: Key Transitions

npm 7 (Node 16) was the biggest shift: workspaces, auto-install peer deps (breaking change from npm 3-6 behavior), `package-lock.json` v2. npm 9-10 focused on `npm query` (CSS-selector dependency querying), `--sbom` for audit, and performance.

### Yarn Berry (2.x+)

Complete rewrite with Plug'n'Play (PnP): eliminates `node_modules`, maps imports to zip archives via `.pnp.cjs`. Near-instant installs and strict isolation, but breaks tools that crawl `node_modules`. Fallback: `nodeLinker: node-modules`.

### pnpm

Content-addressable store with hard links. Each package version stored once globally; projects link to it. **Strict by default**: undeclared dependencies fail at runtime (no phantom deps). Fastest for cold installs. Monorepo via `pnpm-workspace.yaml`. **pnpm 10 (Jan 2025)** disables lifecycle scripts by default for security — `postinstall`/`preinstall` from transitive deps must be explicitly allow-listed via `onlyBuiltDependencies` / `pnpm approve-builds`.

### Corepack

Node.js built-in tool for transparent package manager version management:

```json
{ "packageManager": "pnpm@9.1.0" }
```

```bash
corepack enable  # ships with Node 16+, disabled by default
pnpm install     # automatically uses pnpm@9.1.0
```

---

## 5. Runtime Alternatives

### Node vs Deno vs Bun

| Feature              | Node.js 22       | Deno 2.x          | Bun 1.x          |
|---------------------|------------------|--------------------|-------------------|
| Engine              | V8               | V8                 | JavaScriptCore    |
| Language            | C++              | Rust               | Zig               |
| TypeScript          | Strip types (exp)| Native             | Native            |
| Security model      | Permission (exp) | Secure by default  | None              |
| npm compat          | Native           | High (`npm:`)      | Very high         |
| Startup time        | ~40ms            | ~25ms              | ~7ms              |
| Production maturity | Highest          | High               | Medium            |
| Built-in bundler    | No               | No                 | Yes               |
| Built-in SQLite     | Experimental     | No                 | Yes               |
| Formatter/linter    | No               | Yes                | No                |

### Deno

Created by Ryan Dahl to address Node's design regrets. Security-first (permissions required for FS, network, env). Native TypeScript. npm compatibility via `npm:` prefix. Deno Deploy for edge hosting.

### Bun

Zig-based, JavaScriptCore engine. 3-5x faster startup than Node. Built-in bundler, SQLite driver, Jest-compatible test runner. Fastest package installer. Best for performance-critical tooling and development servers.

### WinterCG

W3C community group defining minimum common APIs across runtimes (Node, Deno, Bun, Cloudflare Workers). Standard surface: `fetch`, `Request`, `Response`, `URL`, `crypto.subtle`, Web Streams, `AbortController`, `structuredClone`.

### When to Choose

- **Node**: Large existing codebase, maximum ecosystem, enterprise requirements, native addon needs
- **Deno**: Greenfield + security requirements, TypeScript-first, built-in tooling
- **Bun**: Performance is primary concern, embedded SQLite needed, fast dev tooling

---

## 6. JavaScript Language Evolution (ES2020-ES2025)

### ES2020

```typescript
user?.address?.city         // Optional chaining
config.port ?? 3000         // Nullish coalescing (null/undefined only, not 0/'')
9007199254740993n           // BigInt
globalThis.fetch            // Universal global
await Promise.allSettled([fetchA, fetchB]) // Never rejects
```

### ES2021

```typescript
'aabbcc'.replaceAll('b', 'x')  // 'aaxxcc'
await Promise.any([f1, f2])    // First fulfilled wins
x ??= computeDefault()         // Logical assignment
```

`WeakRef` and `FinalizationRegistry` for advanced caching patterns.

### ES2022

```typescript
const config = await loadConfig();           // Top-level await
[1,2,3].at(-1)                               // 3 (negative indexing)
Object.hasOwn({ a: 1 }, 'a')                // true
throw new Error('fail', { cause: original }) // Error chaining

class Counter {
  #count = 0;                     // Private field
  static instances = 0;           // Static field
  static { /* init block */ }     // Static initialization
}
```

### ES2023 — Immutable Array Methods

```typescript
const arr = [3, 1, 4, 1, 5];
arr.toSorted()              // [1, 1, 3, 4, 5] — original unchanged
arr.toReversed()            // [5, 1, 4, 1, 3]
arr.toSpliced(2, 1, 9)     // [3, 1, 9, 1, 5]
arr.with(2, 99)             // [3, 1, 99, 1, 5]
arr.findLast(x => x > 3)   // 5
```

### ES2024

```typescript
Object.groupBy(people, p => p.dept)  // { Engineering: [...], Marketing: [...] }
Map.groupBy(words, w => w.length)    // Map { 3 => [...], 5 => [...] }
const { promise, resolve, reject } = Promise.withResolvers()
'hello'.isWellFormed()               // true (Unicode validation)
```

### ES2025 — Set Methods and Iterator Helpers

```typescript
const a = new Set([1,2,3,4]), b = new Set([3,4,5,6]);
a.intersection(b)        // Set {3, 4}
a.union(b)               // Set {1, 2, 3, 4, 5, 6}
a.difference(b)          // Set {1, 2}
a.symmetricDifference(b) // Set {1, 2, 5, 6}

// Iterator helpers (lazy)
naturals().filter(n => n % 2 === 0).map(n => n**2).take(5).toArray()
// [4, 16, 36, 64, 100]
```

### TC39 Proposals to Watch

| Proposal | Stage | Description |
|----------|-------|-------------|
| Pipe operator `\|>` | 2 | `value \|> fn1 \|> fn2` |
| Pattern matching | 2 | `match (value) { when ... }` |
| Signals | 1 | Reactive primitives for UI frameworks |
| Records & Tuples | 2 | Deeply immutable `#{ x: 1 }`, `#[1,2]` |
| Temporal | 3 | Modern date/time API replacing `Date` |

---

## 7. Build Tool Evolution

### The Shift

```
2018-2020: Webpack dominance
2020:      esbuild (Go) — 10-100x faster
2021:      Vite 2.0 (esbuild + Rollup)
2022:      Turbopack announced (Rust)
2023:      Rspack (Rust, Webpack-compatible)
2024:      Rolldown (Rust Rollup replacement for Vite)
2025:      Vite 6 with Rolldown, Turbopack stable in Next.js
```

Webpack's JavaScript pipeline is powerful but slow (30-60s cold start, 1-5s HMR). Native-speed tools and unbundled dev servers changed everything.

### esbuild (Go)

10-100x faster than Webpack. No type checking by design. Limited plugin API, no HMR. Primary role in 2025: dependency pre-bundler for Vite and fast transpiler.

### Vite — The New Default

Serves source as native ES modules in dev (no bundling). Uses Rollup/Rolldown for production. Instant server start, fast HMR, works out of the box for React/Vue/Svelte.

### Turbopack (Rust, Vercel)

Incremental computation engine for Next.js. Function-level caching. Not yet a standalone bundler.

### Rspack (Rust, Webpack-compatible)

Drop-in Webpack replacement, 5-10x faster. Pragmatic for teams with large Webpack configs.

### SWC vs Babel vs tsc

| Tool    | Lang | Speed   | Type Check | Use Case                    |
|---------|------|---------|------------|-----------------------------|
| SWC     | Rust | 20-70x  | No         | Transpilation (default in Next.js, Vite) |
| Babel   | JS   | 1x      | No         | Legacy, specific plugins only |
| tsc     | JS   | Slow    | Yes        | Type checking (`--noEmit`)  |
| esbuild | Go   | 10-100x | No         | Bundling, dep pre-bundling  |

### TypeScript Execution Tools

| Tool     | Purpose              | Engine  | Recommended For           |
|----------|----------------------|---------|---------------------------|
| `tsx`    | Run `.ts` directly   | esbuild | Development (fast, ESM)   |
| `ts-node`| Run `.ts` directly   | tsc     | Legacy projects           |
| `tsup`   | Bundle for npm       | esbuild | Library publishing        |

### When to Use What

| Scenario                  | Tool                              |
|--------------------------|-----------------------------------|
| New SPA                  | Vite                              |
| Next.js app              | Turbopack (dev)                   |
| Library for npm          | tsup or Rollup                    |
| Migrating from Webpack   | Rspack (drop-in) or Vite (rewrite)|
| Node.js backend          | tsx (dev) + tsc (build)           |
| Micro-frontends          | Webpack 5 Module Federation       |

---

## 8. Monorepo & Tooling Evolution

### Why Monorepos

Atomic cross-package changes, shared tooling, dependency consistency, code sharing without npm publishing. The challenge is scale: orchestration tools provide caching and parallelism to keep builds fast.

### Turborepo

Fast task execution through caching and parallelism. Minimal config (`turbo.json` defines tasks, dependencies, outputs). Remote caching via Vercel. `turbo build --filter=...[main]` runs only changed packages.

### Nx

Full-featured toolkit: project graph visualization (`nx graph`), generators (`nx generate`), framework plugins, distributed execution via Nx Cloud. Module boundary ESLint rules enforce architecture. `nx affected -t test` runs tests only for affected projects.

### Lerna (Revived by Nx)

Abandoned 2022, revived by Nx team. Value is versioning and publishing, not task orchestration (delegated to Nx).

### Changesets

Standard versioning tool for monorepos. `npx changeset` (describe change), `npx changeset version` (bump + changelog), `npx changeset publish` (npm publish). Decouples describing changes (PR time) from releasing (merge time).

### Comparison Table

| Feature              | Turborepo       | Nx              | Lerna + Nx      |
|---------------------|-----------------|-----------------|-----------------|
| Task orchestration  | Yes             | Yes             | Via Nx          |
| Local caching       | Yes             | Yes             | Via Nx          |
| Remote caching      | Vercel          | Nx Cloud        | Nx Cloud        |
| Distributed execution| No             | Yes             | Yes             |
| Code generators     | No              | Yes             | No              |
| Graph visualization | No              | Yes             | Via Nx          |
| Module boundaries   | No              | Yes             | No              |
| Version management  | No (Changesets) | Yes             | Yes             |
| Learning curve      | Low             | Medium-High     | Low             |

### Monorepo vs Polyrepo

| Factor              | Monorepo                           | Polyrepo                          |
|--------------------|-------------------------------------|-----------------------------------|
| Team structure     | Single/tightly coupled teams        | Autonomous teams                  |
| Deployment         | Coordinated releases                | Independent schedules             |
| Code sharing       | Heavy shared libraries              | Minimal cross-project deps        |
| CI complexity      | Long pipelines (caching helps)      | Simple per-repo pipelines         |
| Access control     | Everyone sees everything            | Per-project restrictions          |
| Tooling            | Unified                             | Teams choose own tools            |

Pragmatic middle ground: several focused monorepos for related packages, polyrepos for truly independent services.

---
