# Testing — Senior Engineer Interview Preparation

---

## 1. Testing Pyramid

### The Classic Pyramid

The testing pyramid (Mike Cohn) defines three layers of automated tests, each with different cost/speed/confidence trade-offs:

```
        /  E2E  \          Few, slow, expensive, high confidence
       /----------\
      / Integration \      Moderate number, moderate speed
     /----------------\
    /    Unit Tests     \  Many, fast, cheap, low integration confidence
   /____________________\
```

| Layer       | Speed      | Cost to Write/Maintain | Confidence in Integration | Typical Count |
|-------------|------------|------------------------|---------------------------|---------------|
| Unit        | < 10ms     | Low                    | Low                       | Thousands     |
| Integration | 100ms-10s  | Medium                 | Medium-High               | Hundreds      |
| E2E         | 10s-mins   | High                   | High                      | Tens          |

### Unit Tests

Test a single function, class, or module in isolation. External dependencies are mocked or stubbed.

```typescript
// src/services/pricing.ts
export function calculateFinalPrice(
  basePrice: number,
  taxRate: number,
  discountPercent: number
): number {
  const taxed = basePrice * (1 + taxRate);
  const discount = taxed * (discountPercent / 100);
  return Math.round((taxed - discount) * 100) / 100;
}

// src/services/__tests__/pricing.test.ts
import { describe, it, expect } from 'vitest';
import { calculateFinalPrice } from '../pricing';

describe('calculateFinalPrice', () => {
  it('should apply tax then discount', () => {
    // Arrange
    const basePrice = 100;
    const taxRate = 0.2;
    const discount = 10;

    // Act
    const result = calculateFinalPrice(basePrice, taxRate, discount);

    // Assert
    expect(result).toBe(108); // 100 * 1.2 = 120, 120 * 0.9 = 108
  });

  it('should handle zero discount', () => {
    expect(calculateFinalPrice(50, 0.1, 0)).toBe(55);
  });

  it('should round to two decimal places', () => {
    expect(calculateFinalPrice(33.33, 0.07, 5)).toBe(33.86);
  });
});
```

**When to use:** Pure business logic, algorithms, data transformations, validation rules, utility functions.

### Integration Tests

Test the interaction between multiple components or with external systems (database, HTTP API, message broker).

```typescript
import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import request from 'supertest';
import { app } from '../app';
import { db } from '../database';

describe('POST /api/orders', () => {
  beforeAll(async () => {
    await db.migrate.latest();
    await db.seed.run();
  });

  afterAll(async () => {
    await db.destroy();
  });

  it('should create an order and persist it', async () => {
    const response = await request(app)
      .post('/api/orders')
      .send({ items: [{ sku: 'WIDGET-01', quantity: 2 }] })
      .expect(201);

    expect(response.body).toMatchObject({
      id: expect.any(String),
      status: 'PENDING',
      items: [{ sku: 'WIDGET-01', quantity: 2 }],
    });

    // Verify it was actually persisted
    const order = await db('orders').where({ id: response.body.id }).first();
    expect(order).toBeDefined();
    expect(order.status).toBe('PENDING');
  });
});
```

**When to use:** Repository/DAO testing, API endpoint testing, message producer/consumer testing, middleware chains, cache integration.

### End-to-End (E2E) Tests

Test the full system from the user's perspective, typically through the browser or API gateway.

```typescript
import { test, expect } from '@playwright/test';

test('should complete checkout flow', async ({ page }) => {
  await page.goto('/login');
  await page.fill('[data-testid="email"]', 'customer@test.com');
  await page.fill('[data-testid="password"]', 'password123');
  await page.click('[data-testid="login-btn"]');

  await page.click('[data-testid="add-to-cart-WIDGET"]');
  await page.click('[data-testid="cart-icon"]');
  await page.click('[data-testid="checkout-btn"]');

  await page.fill('[data-testid="address"]', '123 Test St');
  await page.click('[data-testid="place-order"]');

  await expect(page.locator('[data-testid="order-status"]')).toHaveText('CONFIRMED');
});
```

**When to use:** Critical user journeys (checkout, registration, onboarding), smoke tests after deployment.

### Anti-Patterns

**Ice Cream Cone (Inverted Pyramid):** Too many E2E tests, few unit tests. Results in slow, flaky CI pipelines and long feedback loops. Common in teams that test only through the UI.

**Hourglass:** Many unit tests, many E2E tests, but few integration tests. The integration layer is where most real bugs live (serialization, SQL queries, API contracts). This pattern gives a false sense of security.

### The Testing Trophy (Kent C. Dodds)

An alternative model for frontend and service-heavy code that emphasizes integration tests as the sweet spot:

```
         / E2E \
        /--------\
       / Integra- \        <-- Emphasis here (highest ROI)
      /   tion     \
     /--------------\
    /  Unit  | Static \
   /____________________\
```

The four layers:
- **Static analysis:** TypeScript compiler, ESLint, Prettier catch bugs at authoring time with zero cost
- **Unit tests:** Fast but low confidence in how components work together
- **Integration tests:** Test several modules collaborating; highest confidence-per-time-invested ratio
- **E2E tests:** Full system confidence but slow and brittle

The key insight: a single integration test that hits a real database catches more real-world bugs than ten unit tests with a mocked repository. Static analysis (TypeScript) also eliminates entire categories of bugs for free.

### When to Use Which Level

| Scenario | Recommended Level | Rationale |
|----------|------------------|-----------|
| Pure function, algorithm | Unit | No dependencies, fast feedback |
| Express middleware chain | Integration | Tests real request/response cycle |
| Database query with joins | Integration (Testcontainers) | SQL logic cannot be unit tested meaningfully |
| Stripe webhook handler | Integration (mock HTTP) | Contract validation matters |
| User registration flow | E2E | Crosses multiple services, UI, email |
| Visual appearance | E2E (visual regression) | Pixel-level validation |
| Type correctness | Static (TypeScript) | Caught at compile time |

---

## 2. Test Frameworks

### Jest

**Architecture:** Jest is a batteries-included test framework built by Meta. It uses a custom module resolution system that wraps Node's `require()` to enable module mocking. Each test file runs in its own sandboxed `vm` context (by default) for isolation.

Key architectural components:
- **jest-haste-map:** File watcher and module resolver (originally from Metro bundler)
- **jest-runtime:** Custom module system that intercepts `require()` calls
- **jest-circus:** Default test runner implementing `describe`, `it`, `beforeEach`
- **jest-snapshot:** Serializes values to `.snap` files for regression detection

```typescript
// jest.config.ts
import type { Config } from 'jest';

const config: Config = {
  preset: 'ts-jest',
  testEnvironment: 'node',
  roots: ['<rootDir>/src'],
  moduleNameMapper: {
    '^@/(.*)$': '<rootDir>/src/$1',        // Path aliases
    '\\.(css|less)$': 'identity-obj-proxy', // CSS modules
  },
  setupFilesAfterFramework: ['<rootDir>/jest.setup.ts'],
  coverageProvider: 'v8',                   // Faster than babel/istanbul
  transform: {
    '^.+\\.tsx?$': ['ts-jest', { tsconfig: 'tsconfig.test.json' }],
  },
};

export default config;
```

**Module mocking** is Jest's most powerful and most misunderstood feature:

```typescript
// Jest intercepts require() at the module system level
jest.mock('./database', () => ({
  query: jest.fn().mockResolvedValue([{ id: 1, name: 'Alice' }]),
  connect: jest.fn(),
}));

// The mock HOISTS to the top of the file, even though it appears here
// This is achieved via a Babel transform that moves jest.mock() calls
import { query } from './database'; // This import gets the mocked version
```

**Snapshot testing** captures serialized output and compares against stored baselines:

```typescript
it('should render user profile correctly', () => {
  const profile = renderToString(<UserProfile user={testUser} />);
  expect(profile).toMatchSnapshot();
  // First run: creates __snapshots__/profile.test.ts.snap
  // Subsequent runs: compares against stored snapshot
});

// Inline snapshots — stored directly in the test file
it('should format address', () => {
  expect(formatAddress(testAddress)).toMatchInlineSnapshot(`
    "123 Main St
    Suite 400
    San Francisco, CA 94102"
  `);
});
```

**Limitations:**
- Custom module system causes ESM incompatibilities (requires `--experimental-vm-modules`)
- Startup is slow due to module graph analysis and Babel/ts-jest transforms
- `jest.mock()` hoisting is magical and causes confusion with variable scoping

### Vitest

**Architecture:** Vitest is a Vite-powered test framework that reuses Vite's dev server and module transform pipeline. Because it uses Vite's native ESM handling, it avoids the module system gymnastics that Jest requires.

```typescript
// vitest.config.ts
import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    globals: true,               // inject describe, it, expect globally
    environment: 'node',         // or 'jsdom', 'happy-dom'
    include: ['src/**/*.{test,spec}.{ts,tsx}'],
    coverage: {
      provider: 'v8',           // or 'istanbul'
      reporter: ['text', 'lcov', 'html'],
      thresholds: {
        branches: 80,
        functions: 80,
        lines: 80,
      },
    },
    pool: 'forks',               // 'threads' (default), 'forks', or 'vmThreads'
    setupFiles: ['./vitest.setup.ts'],
  },
  resolve: {
    alias: { '@': './src' },     // Shares Vite's resolve config
  },
});
```

**Key advantages over Jest:**
- Native ESM support — no transform hacks needed
- Shares Vite's config, plugins, and resolve pipeline (one config for dev + test)
- Watch mode uses Vite's HMR graph for intelligent re-runs (only re-runs tests affected by changed files)
- Jest-compatible API — migration is often a matter of changing imports

```typescript
import { describe, it, expect, vi } from 'vitest';

// vi.mock() works like jest.mock() but with proper ESM semantics
vi.mock('./database', () => ({
  query: vi.fn().mockResolvedValue([{ id: 1, name: 'Alice' }]),
}));

// vi.hoisted() — explicitly hoist variables for use in vi.mock()
const mockQuery = vi.hoisted(() => vi.fn());
vi.mock('./database', () => ({
  query: mockQuery,
}));
```

### `node:test` Built-in Test Runner

Node.js 18+ includes a built-in test runner with zero dependencies. Node 20+ made it stable.

```typescript
import { describe, it, before, after, mock } from 'node:test';
import assert from 'node:assert/strict';

describe('UserService', () => {
  let service: UserService;

  before(() => {
    service = new UserService();
  });

  it('should create a user', async () => {
    const user = await service.create({ name: 'Alice', email: 'alice@test.com' });
    assert.equal(user.name, 'Alice');
    assert.ok(user.id);
  });

  it('should reject duplicate emails', async () => {
    await service.create({ name: 'Bob', email: 'bob@test.com' });
    await assert.rejects(
      () => service.create({ name: 'Bob2', email: 'bob@test.com' }),
      { code: 'DUPLICATE_EMAIL' }
    );
  });

  // Mocking with node:test
  it('should call repository', async (t) => {
    const mockSave = t.mock.fn(async (user: User) => ({ ...user, id: '123' }));
    const svc = new UserService({ save: mockSave } as any);

    await svc.create({ name: 'Test', email: 'test@test.com' });

    assert.equal(mockSave.mock.calls.length, 1);
    assert.equal(mockSave.mock.calls[0].arguments[0].name, 'Test');
  });
});
```

Run with: `node --test --experimental-strip-types src/**/*.test.ts`

### Comparison Table

| Feature | Jest | Vitest | node:test |
|---------|------|--------|-----------|
| ESM Support | Experimental (`--experimental-vm-modules`) | Native | Native |
| TypeScript | Via `ts-jest` or `@swc/jest` | Via Vite transforms (esbuild) | `--experimental-strip-types` (Node 22+) |
| Module Mocking | `jest.mock()` with hoisting | `vi.mock()` with `vi.hoisted()` | `mock.module()` (experimental) |
| Watch Mode | File-system based | Vite HMR graph (smarter) | `--watch` (basic) |
| Snapshot Testing | Built-in | Built-in (compatible) | `assert.snapshot()` (Node 22+) |
| Configuration | `jest.config.ts` | `vitest.config.ts` (shares `vite.config`) | CLI flags / minimal |
| Speed (cold start) | Slow (module graph analysis) | Fast (Vite pipeline) | Fastest (zero overhead) |
| Speed (re-run) | Moderate | Fast (HMR-aware) | Fast |
| Ecosystem | Largest (mature) | Growing fast | Minimal |
| Parallel Execution | Workers (separate processes) | Threads or forks | Threads (default) |
| Browser Testing | Via jsdom | Via browser mode (real Chromium) | No |
| Coverage | v8 or istanbul | v8 or istanbul | `--experimental-test-coverage` |
| Matchers | `expect()` rich API | `expect()` (Jest-compatible) | `assert` (Node standard) |
| Dependencies | Heavy (~50MB) | Moderate (~15MB) | Zero |

**Recommendation:** Use **Vitest** for new projects (best DX, native ESM, fast). Use **Jest** if you have an existing large test suite. Use **node:test** for libraries with minimal dependencies or when you need zero external deps.

---

## 3. Mocking Strategies

### Test Doubles Taxonomy

Gerard Meszaros defined five types of test doubles. Understanding the taxonomy prevents over-mocking:

| Double | Behavior | Verification | Example |
|--------|----------|-------------|---------|
| **Dummy** | Passed but never used | None | `null` passed to satisfy a parameter |
| **Fake** | Working implementation, simplified | None (it works) | In-memory database, fake SMTP server |
| **Stub** | Returns predetermined values | None | `vi.fn().mockReturnValue(42)` |
| **Spy** | Records calls, delegates to real impl | Call count, arguments | `vi.spyOn(obj, 'method')` |
| **Mock** | Pre-programmed expectations | Verifies calls were made | `expect(mock).toHaveBeenCalledWith(...)` |

### `vi.fn()`, `vi.spyOn()`, `vi.mock()` (Vitest)

```typescript
import { describe, it, expect, vi, beforeEach } from 'vitest';

// vi.fn() — create a standalone mock function
describe('vi.fn()', () => {
  it('should track calls and allow return value configuration', () => {
    const callback = vi.fn()
      .mockReturnValueOnce('first')
      .mockReturnValueOnce('second')
      .mockReturnValue('default');

    expect(callback()).toBe('first');
    expect(callback()).toBe('second');
    expect(callback()).toBe('default');
    expect(callback).toHaveBeenCalledTimes(3);
  });

  it('should support async mock implementations', async () => {
    const fetchUser = vi.fn<[string], Promise<User>>()
      .mockResolvedValueOnce({ id: '1', name: 'Alice' })
      .mockRejectedValueOnce(new Error('Not found'));

    const user = await fetchUser('1');
    expect(user.name).toBe('Alice');

    await expect(fetchUser('999')).rejects.toThrow('Not found');
  });

  it('should support custom implementation', () => {
    const add = vi.fn((a: number, b: number) => a + b);
    expect(add(2, 3)).toBe(5);
    expect(add).toHaveBeenCalledWith(2, 3);
  });
});

// vi.spyOn() — spy on an existing object method
describe('vi.spyOn()', () => {
  it('should spy without changing behavior', () => {
    const calculator = {
      add: (a: number, b: number) => a + b,
    };

    const spy = vi.spyOn(calculator, 'add');

    expect(calculator.add(2, 3)).toBe(5); // Real implementation called
    expect(spy).toHaveBeenCalledWith(2, 3);
    expect(spy).toHaveReturnedWith(5);
  });

  it('should allow overriding the implementation', () => {
    const service = new OrderService(realRepo);
    const spy = vi.spyOn(service, 'calculateTotal')
      .mockReturnValue(999);

    expect(service.calculateTotal(order)).toBe(999);
    spy.mockRestore(); // Restore original implementation
  });

  it('should spy on getters and setters', () => {
    const config = { get timeout() { return 5000; } };
    const spy = vi.spyOn(config, 'timeout', 'get').mockReturnValue(100);
    expect(config.timeout).toBe(100);
  });
});
```

### Module Mocking

```typescript
// --- Automatic mock (all exports become vi.fn()) ---
vi.mock('./emailService');
// Every export from emailService is now a vi.fn() that returns undefined

import { sendEmail } from './emailService';

it('auto-mocked sendEmail is a vi.fn()', () => {
  expect(vi.isMockFunction(sendEmail)).toBe(true);
  sendEmail('test@test.com', 'Hello');
  expect(sendEmail).toHaveBeenCalledWith('test@test.com', 'Hello');
});

// --- Factory mock (custom implementation) ---
vi.mock('./database', () => ({
  default: {
    query: vi.fn(),
    transaction: vi.fn((cb: Function) => cb()),
  },
}));

// --- Partial mock (keep some real implementations) ---
vi.mock('./utils', async (importOriginal) => {
  const actual = await importOriginal<typeof import('./utils')>();
  return {
    ...actual,
    generateId: vi.fn().mockReturnValue('fixed-id-123'),
    // All other exports remain real
  };
});

// --- Manual mocks (__mocks__ directory) ---
// src/services/__mocks__/stripe.ts
export const createPaymentIntent = vi.fn().mockResolvedValue({
  id: 'pi_test_123',
  status: 'succeeded',
  amount: 1000,
});

export const refund = vi.fn().mockResolvedValue({ id: 'rf_test_123' });
// Then in tests: vi.mock('./stripe') picks up __mocks__/stripe.ts automatically
```

### Mocking `node:` Built-ins

```typescript
import { vi, describe, it, expect } from 'vitest';
import { vol } from 'memfs';

// Option 1: vi.mock with node: protocol
vi.mock('node:fs/promises', () => ({
  readFile: vi.fn().mockResolvedValue('mocked file content'),
  writeFile: vi.fn().mockResolvedValue(undefined),
  mkdir: vi.fn().mockResolvedValue(undefined),
}));

import { readFile } from 'node:fs/promises';

it('should use mocked fs', async () => {
  const content = await readFile('/any/path', 'utf-8');
  expect(content).toBe('mocked file content');
});

// Option 2: memfs — full in-memory filesystem (better for complex tests)
vi.mock('node:fs', async () => {
  const memfs = await import('memfs');
  return memfs.fs;
});
vi.mock('node:fs/promises', async () => {
  const memfs = await import('memfs');
  return memfs.fs.promises;
});

it('should work with in-memory filesystem', () => {
  vol.fromJSON({
    '/config/app.json': '{"port": 3000}',
    '/data/users.csv': 'name,email\nAlice,alice@test.com',
  });

  // Now any code importing fs reads from in-memory volume
});
```

### Dependency Injection for Testability

The most maintainable approach avoids module-level mocking entirely by injecting dependencies:

```typescript
// --- Production code ---
interface UserRepository {
  findById(id: string): Promise<User | null>;
  save(user: User): Promise<User>;
}

interface EmailService {
  send(to: string, subject: string, body: string): Promise<void>;
}

class UserService {
  constructor(
    private readonly userRepo: UserRepository,
    private readonly emailService: EmailService,
    private readonly clock: () => Date = () => new Date()
  ) {}

  async register(input: RegisterInput): Promise<User> {
    const existing = await this.userRepo.findById(input.email);
    if (existing) throw new DuplicateUserError(input.email);

    const user: User = {
      id: crypto.randomUUID(),
      ...input,
      createdAt: this.clock(),
    };

    const saved = await this.userRepo.save(user);
    await this.emailService.send(saved.email, 'Welcome!', `Hi ${saved.name}`);
    return saved;
  }
}

// --- Test code ---
describe('UserService', () => {
  const mockRepo: UserRepository = {
    findById: vi.fn().mockResolvedValue(null),
    save: vi.fn(async (user) => user),
  };

  const mockEmail: EmailService = {
    send: vi.fn().mockResolvedValue(undefined),
  };

  const fixedDate = new Date('2025-01-01T00:00:00Z');
  const fixedClock = () => fixedDate;

  let service: UserService;

  beforeEach(() => {
    vi.clearAllMocks();
    service = new UserService(mockRepo, mockEmail, fixedClock);
  });

  it('should register a new user', async () => {
    const user = await service.register({ name: 'Alice', email: 'alice@test.com' });

    expect(user.name).toBe('Alice');
    expect(user.createdAt).toBe(fixedDate);
    expect(mockRepo.save).toHaveBeenCalledOnce();
    expect(mockEmail.send).toHaveBeenCalledWith(
      'alice@test.com', 'Welcome!', 'Hi Alice'
    );
  });

  it('should reject duplicate emails', async () => {
    vi.mocked(mockRepo.findById).mockResolvedValueOnce({ id: '1', name: 'Existing' } as User);

    await expect(service.register({ name: 'Alice', email: 'alice@test.com' }))
      .rejects.toThrow(DuplicateUserError);
    expect(mockRepo.save).not.toHaveBeenCalled();
  });
});
```

### When NOT to Mock (Over-Mocking Anti-Pattern)

**Signs you are over-mocking:**
1. Test mirrors the implementation line-by-line — passes when code is right, still passes when logic is wrong
2. Refactoring internal details breaks tests even when behavior is unchanged
3. `vi.mock()` calls outnumber actual assertions
4. You mock the thing you are testing (mocking SUT methods)

```typescript
// BAD — over-mocked test that tests nothing useful
it('should process order', async () => {
  vi.mocked(validateOrder).mockReturnValue(true);
  vi.mocked(calculateTotal).mockReturnValue(100);
  vi.mocked(chargePayment).mockResolvedValue({ success: true });
  vi.mocked(saveOrder).mockResolvedValue({ id: '1' });
  vi.mocked(sendConfirmation).mockResolvedValue(undefined);

  await processOrder(testOrder);

  // These assertions just verify the functions were called in order
  // They would pass even if processOrder had completely wrong logic
  expect(validateOrder).toHaveBeenCalled();
  expect(calculateTotal).toHaveBeenCalled();
  expect(chargePayment).toHaveBeenCalled();
  expect(saveOrder).toHaveBeenCalled();
  expect(sendConfirmation).toHaveBeenCalled();
});

// BETTER — test the actual behavior with integration test
it('should process order end-to-end', async () => {
  const order = await processOrder({
    items: [{ sku: 'WIDGET', quantity: 2, price: 10 }],
    payment: testPaymentMethod,
  });

  expect(order.total).toBe(20);
  expect(order.status).toBe('CONFIRMED');

  const saved = await db.orders.findById(order.id);
  expect(saved).toBeDefined();
});
```

**Rule of thumb:** Mock at the architectural boundary (network, filesystem, third-party APIs). Do not mock your own code unless you have a specific reason to isolate one layer.

---

## 4. Integration Testing

### Supertest for HTTP Endpoint Testing

Supertest binds to an Express/Koa/Fastify app without starting a real HTTP server, making tests fast and deterministic.

```typescript
import request from 'supertest';
import { describe, it, expect, beforeEach } from 'vitest';
import { createApp } from '../app';

describe('Orders API', () => {
  let app: Express.Application;

  beforeEach(async () => {
    app = await createApp({ database: testDb });
  });

  it('POST /api/orders should create an order', async () => {
    const response = await request(app)
      .post('/api/orders')
      .set('Authorization', `Bearer ${testToken}`)
      .set('Content-Type', 'application/json')
      .send({
        items: [{ sku: 'WIDGET-01', quantity: 3 }],
        shippingAddress: { street: '123 Main St', city: 'SF', zip: '94102' },
      })
      .expect(201)
      .expect('Content-Type', /json/);

    expect(response.body).toMatchObject({
      id: expect.stringMatching(/^ord_/),
      status: 'PENDING',
      total: expect.any(Number),
    });
  });

  it('should return 422 for invalid payload', async () => {
    const response = await request(app)
      .post('/api/orders')
      .set('Authorization', `Bearer ${testToken}`)
      .send({ items: [] }) // Empty items
      .expect(422);

    expect(response.body.errors).toContainEqual(
      expect.objectContaining({ field: 'items', message: expect.stringContaining('at least one') })
    );
  });

  it('should return 401 without auth token', async () => {
    await request(app)
      .post('/api/orders')
      .send({ items: [{ sku: 'X', quantity: 1 }] })
      .expect(401);
  });

  it('GET /api/orders should paginate results', async () => {
    // Seed 25 orders
    await seedOrders(25);

    const page1 = await request(app)
      .get('/api/orders?page=1&limit=10')
      .set('Authorization', `Bearer ${testToken}`)
      .expect(200);

    expect(page1.body.data).toHaveLength(10);
    expect(page1.body.meta).toMatchObject({
      page: 1,
      totalPages: 3,
      totalCount: 25,
    });
  });
});
```

### Testcontainers for Database Testing

Testcontainers spins up real Docker containers for dependencies. This tests against the actual database engine, not an approximation.

```typescript
import { PostgreSqlContainer, StartedPostgreSqlContainer } from '@testcontainers/postgresql';
import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import { drizzle } from 'drizzle-orm/node-postgres';
import { migrate } from 'drizzle-orm/node-postgres/migrator';
import { Pool } from 'pg';
import * as schema from '../schema';

describe('OrderRepository (Postgres)', () => {
  let container: StartedPostgreSqlContainer;
  let db: ReturnType<typeof drizzle>;
  let pool: Pool;

  beforeAll(async () => {
    container = await new PostgreSqlContainer('postgres:16-alpine')
      .withDatabase('testdb')
      .withExposedPorts(5432)
      .start();

    pool = new Pool({ connectionString: container.getConnectionUri() });
    db = drizzle(pool, { schema });
    await migrate(db, { migrationsFolder: './drizzle' });
  }, 30_000); // 30s timeout for container startup

  afterAll(async () => {
    await pool.end();
    await container.stop();
  });

  it('should insert and query with JSON columns', async () => {
    const order = await db.insert(schema.orders).values({
      customerId: 'cust_001',
      metadata: { source: 'web', campaign: 'summer2025' },
      items: [{ sku: 'A', qty: 2 }],
    }).returning();

    const found = await db.query.orders.findFirst({
      where: (orders, { eq }) => eq(orders.customerId, 'cust_001'),
    });

    expect(found!.metadata).toEqual({ source: 'web', campaign: 'summer2025' });
  });

  it('should handle concurrent updates with optimistic locking', async () => {
    const [order] = await db.insert(schema.orders).values({
      customerId: 'cust_002',
      status: 'PENDING',
      version: 1,
    }).returning();

    // Simulate two concurrent updates
    const update1 = db.update(schema.orders)
      .set({ status: 'CONFIRMED', version: 2 })
      .where(and(eq(schema.orders.id, order.id), eq(schema.orders.version, 1)));

    const update2 = db.update(schema.orders)
      .set({ status: 'CANCELLED', version: 2 })
      .where(and(eq(schema.orders.id, order.id), eq(schema.orders.version, 1)));

    const [result1, result2] = await Promise.all([update1, update2]);
    // One succeeds, one is a no-op (0 rows affected)
    const totalUpdated = result1.rowCount! + result2.rowCount!;
    expect(totalUpdated).toBe(1);
  });
});
```

### In-Memory Databases — Tradeoffs

| Approach | Fidelity | Speed | Setup Complexity |
|----------|----------|-------|-----------------|
| Testcontainers (real Postgres) | Perfect | Slow (container startup) | Medium (Docker required) |
| SQLite (via `better-sqlite3`) | Low (different SQL dialect) | Fast | Low |
| `pg-mem` (in-memory Postgres) | Medium (subset of Postgres) | Fast | Low |
| Shared Testcontainer (reuse) | Perfect | Fast after first test | Medium |

**Critical SQLite differences that break Postgres tests:**
- No `RETURNING` clause (before SQLite 3.35)
- No `JSONB` operators (`->`, `->>`, `@>`)
- No `ARRAY` types
- Different `ON CONFLICT` syntax
- No `LISTEN`/`NOTIFY`
- Different transaction isolation semantics

**Recommendation:** Use Testcontainers with container reuse enabled. Start the container once per test suite (not per test), use transactions to isolate tests, and roll back after each test.

### API Contract Testing (Pact)

Pact verifies that a consumer and provider agree on the API contract, without requiring both to run simultaneously.

```typescript
// --- Consumer test (frontend or downstream service) ---
import { PactV3, MatchersV3 } from '@pact-foundation/pact';

const provider = new PactV3({
  consumer: 'OrdersUI',
  provider: 'OrdersAPI',
  logLevel: 'warn',
});

describe('Orders API Contract', () => {
  it('should return order by ID', async () => {
    await provider
      .given('order ORD-001 exists')
      .uponReceiving('a request for order ORD-001')
      .withRequest({
        method: 'GET',
        path: '/api/orders/ORD-001',
        headers: { Accept: 'application/json' },
      })
      .willRespondWith({
        status: 200,
        headers: { 'Content-Type': 'application/json' },
        body: MatchersV3.like({
          id: 'ORD-001',
          status: MatchersV3.regex(/PENDING|CONFIRMED|SHIPPED/, 'PENDING'),
          total: MatchersV3.decimal(99.99),
          items: MatchersV3.eachLike({
            sku: MatchersV3.string('WIDGET-01'),
            quantity: MatchersV3.integer(1),
          }),
        }),
      })
      .executeTest(async (mockServer) => {
        const client = new OrdersClient(mockServer.url);
        const order = await client.getOrder('ORD-001');

        expect(order.id).toBe('ORD-001');
        expect(order.items.length).toBeGreaterThan(0);
      });
  });
});

// The contract (pact file) is published to a Pact Broker
// The provider then verifies it independently in its own CI
```

### NestJS Testing Utilities

```typescript
import { Test, TestingModule } from '@nestjs/testing';
import { INestApplication } from '@nestjs/common';
import request from 'supertest';

describe('OrderController (e2e)', () => {
  let app: INestApplication;

  beforeAll(async () => {
    const moduleFixture: TestingModule = await Test.createTestingModule({
      imports: [AppModule],
    })
      .overrideProvider(PaymentService)
      .useValue({
        charge: vi.fn().mockResolvedValue({ success: true, transactionId: 'tx_123' }),
      })
      .overrideProvider(DATABASE_TOKEN)
      .useFactory({
        factory: async () => {
          // Use Testcontainers Postgres
          const pool = new Pool({ connectionString: testContainer.getConnectionUri() });
          return drizzle(pool);
        },
      })
      .compile();

    app = moduleFixture.createNestApplication();
    app.useGlobalPipes(new ValidationPipe({ whitelist: true, transform: true }));
    await app.init();
  });

  afterAll(async () => {
    await app.close();
  });

  it('POST /orders should validate DTO and persist', async () => {
    const response = await request(app.getHttpServer())
      .post('/orders')
      .send({ items: [{ sku: 'A', quantity: 1 }] })
      .expect(201);

    expect(response.body.id).toBeDefined();
  });
});
```

---

## 5. E2E Testing

### Playwright Setup and Page Objects

```typescript
// playwright.config.ts
import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './e2e',
  timeout: 30_000,
  retries: process.env.CI ? 2 : 0,       // Retry flaky tests in CI only
  workers: process.env.CI ? 4 : undefined, // Parallel in CI
  reporter: [
    ['html', { open: 'never' }],
    ['junit', { outputFile: 'test-results/results.xml' }],
  ],
  use: {
    baseURL: 'http://localhost:3000',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
    trace: 'on-first-retry',              // Full trace for debugging retries
  },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
    { name: 'firefox', use: { ...devices['Desktop Firefox'] } },
    { name: 'mobile', use: { ...devices['iPhone 14'] } },
  ],
  webServer: {
    command: 'npm run dev',
    port: 3000,
    reuseExistingServer: !process.env.CI,
  },
});
```

**Page Object Model — encapsulates page interactions:**

```typescript
// e2e/pages/LoginPage.ts
import { type Page, type Locator, expect } from '@playwright/test';

export class LoginPage {
  readonly emailInput: Locator;
  readonly passwordInput: Locator;
  readonly submitButton: Locator;
  readonly errorMessage: Locator;

  constructor(private readonly page: Page) {
    this.emailInput = page.getByLabel('Email');
    this.passwordInput = page.getByLabel('Password');
    this.submitButton = page.getByRole('button', { name: 'Sign in' });
    this.errorMessage = page.getByRole('alert');
  }

  async goto() {
    await this.page.goto('/login');
  }

  async login(email: string, password: string) {
    await this.emailInput.fill(email);
    await this.passwordInput.fill(password);
    await this.submitButton.click();
  }

  async expectError(message: string) {
    await expect(this.errorMessage).toContainText(message);
  }
}

// e2e/pages/DashboardPage.ts
export class DashboardPage {
  constructor(private readonly page: Page) {}

  async expectLoggedInAs(name: string) {
    await expect(this.page.getByTestId('user-name')).toHaveText(name);
  }

  async navigateToOrders() {
    await this.page.getByRole('link', { name: 'Orders' }).click();
  }
}
```

**Fixtures — reusable test setup:**

```typescript
// e2e/fixtures.ts
import { test as base } from '@playwright/test';
import { LoginPage } from './pages/LoginPage';
import { DashboardPage } from './pages/DashboardPage';

type Fixtures = {
  loginPage: LoginPage;
  dashboardPage: DashboardPage;
  authenticatedPage: Page;
};

export const test = base.extend<Fixtures>({
  loginPage: async ({ page }, use) => {
    await use(new LoginPage(page));
  },
  dashboardPage: async ({ page }, use) => {
    await use(new DashboardPage(page));
  },
  authenticatedPage: async ({ browser }, use) => {
    // Reuse stored auth state
    const context = await browser.newContext({
      storageState: 'e2e/.auth/user.json',
    });
    const page = await context.newPage();
    await use(page);
    await context.close();
  },
});

// e2e/auth.setup.ts — runs once, stores auth state
import { test as setup } from '@playwright/test';

setup('authenticate', async ({ page }) => {
  await page.goto('/login');
  await page.getByLabel('Email').fill('test@test.com');
  await page.getByLabel('Password').fill('password123');
  await page.getByRole('button', { name: 'Sign in' }).click();
  await page.waitForURL('/dashboard');
  await page.context().storageState({ path: 'e2e/.auth/user.json' });
});

// e2e/orders.spec.ts — uses fixtures
import { test } from './fixtures';
import { expect } from '@playwright/test';

test.describe('Order management', () => {
  test('should create and view an order', async ({ authenticatedPage }) => {
    const page = authenticatedPage;
    await page.goto('/orders/new');
    await page.getByLabel('Product').selectOption('WIDGET-01');
    await page.getByLabel('Quantity').fill('3');
    await page.getByRole('button', { name: 'Place Order' }).click();

    await expect(page.getByText('Order confirmed')).toBeVisible();
    await expect(page.getByTestId('order-total')).toHaveText('$29.97');
  });
});
```

### Cypress vs Playwright Comparison

| Feature | Playwright | Cypress |
|---------|-----------|---------|
| Architecture | Runs outside browser via CDP/WebDriver BiDi | Runs inside browser (same-origin) |
| Multi-browser | Chromium, Firefox, WebKit | Chromium, Firefox, WebKit (limited) |
| Multi-tab/window | Full support | Not supported |
| iframes | Full support | Partial (same-origin only by default) |
| Auto-waiting | Built-in (actionability checks) | Built-in (retry-ability) |
| Network interception | `page.route()` (full control) | `cy.intercept()` |
| Parallelism | Native (sharding built-in) | Requires Cypress Cloud or third-party |
| Language | TypeScript, JavaScript, Python, Java, C# | TypeScript, JavaScript only |
| CI Speed | Faster (lighter, parallel by default) | Slower (heavier runtime) |
| Debugging | Trace viewer, VS Code extension | Time-travel debugger in Cypress UI |
| Component testing | Experimental | Supported (`cy.mount()`) |
| Mobile testing | Device emulation (viewport + user agent) | Device emulation (viewport only) |
| Test isolation | Browser context per test (true isolation) | Clears state between tests (less isolated) |
| Flakiness | Lower (out-of-process, auto-waiting) | Higher (in-process, timing issues) |

**Recommendation:** Playwright for new projects. Its out-of-process architecture, auto-waiting, and native parallelism make it more reliable and faster in CI.

### Visual Regression Testing

```typescript
import { test, expect } from '@playwright/test';

test('dashboard should match visual baseline', async ({ page }) => {
  await page.goto('/dashboard');
  await page.waitForLoadState('networkidle');

  // Full page screenshot comparison
  await expect(page).toHaveScreenshot('dashboard.png', {
    maxDiffPixels: 100,       // Tolerate minor anti-aliasing differences
    threshold: 0.2,           // Per-pixel color threshold (0-1)
    animations: 'disabled',   // Freeze CSS animations
  });

  // Component-level screenshot
  const chart = page.getByTestId('revenue-chart');
  await expect(chart).toHaveScreenshot('revenue-chart.png', {
    maxDiffPixelRatio: 0.01,  // Max 1% of pixels can differ
  });
});
```

**Handling dynamic content in visual tests:**

```typescript
test('should mask dynamic elements', async ({ page }) => {
  await page.goto('/profile');

  await expect(page).toHaveScreenshot('profile.png', {
    mask: [
      page.getByTestId('avatar'),       // User avatar changes
      page.getByTestId('last-login'),    // Timestamps change
      page.locator('.ad-banner'),        // Dynamic ads
    ],
  });
});
```

### Test Data Management

```typescript
// e2e/utils/test-data.ts
import { Pool } from 'pg';

const pool = new Pool({ connectionString: process.env.TEST_DATABASE_URL });

export async function seedTestData() {
  await pool.query(`
    INSERT INTO users (id, email, name) VALUES
      ('usr_001', 'alice@test.com', 'Alice'),
      ('usr_002', 'bob@test.com', 'Bob')
    ON CONFLICT (id) DO NOTHING;
  `);
}

export async function cleanupTestData() {
  // Truncate in reverse foreign-key order
  await pool.query(`
    TRUNCATE TABLE order_items, orders, users CASCADE;
  `);
}

// Use in global setup
// e2e/global-setup.ts
import { FullConfig } from '@playwright/test';

async function globalSetup(config: FullConfig) {
  await cleanupTestData();
  await seedTestData();
  await runMigrations();
}

export default globalSetup;
```

---

## 6. Testing Patterns & Best Practices

### Arrange-Act-Assert (AAA)

The canonical test structure. Every test should have exactly three clearly separated phases:

```typescript
it('should apply percentage discount to order total', () => {
  // Arrange — set up preconditions and inputs
  const order = new Order([
    { sku: 'A', price: 100, quantity: 2 },
    { sku: 'B', price: 50, quantity: 1 },
  ]);
  const discount = new PercentageDiscount(10);

  // Act — execute the behavior under test
  const discountedTotal = discount.apply(order);

  // Assert — verify the expected outcome
  expect(discountedTotal).toBe(225); // (200 + 50) * 0.9
});
```

**Common violation:** Mixing act and assert (asserting within the act phase, or having multiple act-assert cycles). If you need multiple acts, you likely need multiple tests.

### Given-When-Then (BDD)

Maps directly to AAA but reads as a specification. Useful for acceptance tests and when non-engineers review test cases:

```typescript
describe('Shopping Cart', () => {
  describe('given a cart with items', () => {
    let cart: ShoppingCart;

    beforeEach(() => {
      cart = new ShoppingCart();
      cart.add({ sku: 'LAPTOP', price: 999, quantity: 1 });
      cart.add({ sku: 'MOUSE', price: 29, quantity: 2 });
    });

    describe('when a valid coupon is applied', () => {
      beforeEach(() => {
        cart.applyCoupon('SAVE20');
      });

      it('then the total reflects the discount', () => {
        expect(cart.total).toBe(846.40); // (999 + 58) * 0.8
      });

      it('then the coupon is marked as used', () => {
        expect(cart.appliedCoupon).toBe('SAVE20');
      });
    });

    describe('when an expired coupon is applied', () => {
      it('then it throws an error', () => {
        expect(() => cart.applyCoupon('EXPIRED')).toThrow('Coupon expired');
      });

      it('then the total is unchanged', () => {
        expect(cart.total).toBe(1057);
      });
    });
  });
});
```

### Test Isolation and Idempotency

Tests must not depend on execution order, shared mutable state, or side effects from other tests.

```typescript
// BAD — tests share state, order-dependent
let counter = 0;

it('should increment', () => {
  counter++;
  expect(counter).toBe(1); // Fails if run after another test that increments
});

// GOOD — each test creates its own state
it('should increment counter', () => {
  const counter = new Counter(0);
  counter.increment();
  expect(counter.value).toBe(1);
});

// Database test isolation with transactions
describe('OrderRepository', () => {
  let trx: Transaction;

  beforeEach(async () => {
    trx = await db.transaction();    // Start transaction
  });

  afterEach(async () => {
    await trx.rollback();            // Rollback — no data leaks between tests
  });

  it('should save order', async () => {
    const repo = new OrderRepository(trx); // Use transaction, not raw connection
    await repo.save(testOrder);
    const found = await repo.findById(testOrder.id);
    expect(found).toBeDefined();
  });
  // Transaction rolled back — testOrder does not exist for next test
});
```

### Deterministic Tests

Dealing with sources of non-determinism: dates, randomness, network, environment.

```typescript
// --- Dates: inject clock or use fake timers ---
it('should set expiry to 30 days from now', () => {
  vi.useFakeTimers();
  vi.setSystemTime(new Date('2025-06-01T12:00:00Z'));

  const token = createToken('user123');

  expect(token.expiresAt).toEqual(new Date('2025-07-01T12:00:00Z'));

  vi.useRealTimers();
});

// --- Randomness: inject PRNG or seed ---
import { seedrandom } from 'seedrandom';

function shuffleArray<T>(arr: T[], rng: () => number = Math.random): T[] {
  const result = [...arr];
  for (let i = result.length - 1; i > 0; i--) {
    const j = Math.floor(rng() * (i + 1));
    [result[i], result[j]] = [result[j], result[i]];
  }
  return result;
}

it('should shuffle deterministically with seed', () => {
  const rng = seedrandom('test-seed-42');
  const result = shuffleArray([1, 2, 3, 4, 5], rng);
  expect(result).toEqual([3, 1, 5, 2, 4]); // Same every time
});

// --- Network: intercept external calls ---
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';

const server = setupServer(
  http.get('https://api.stripe.com/v1/charges/:id', () => {
    return HttpResponse.json({ id: 'ch_test', amount: 1000, status: 'succeeded' });
  }),
  http.post('https://api.sendgrid.com/v3/mail/send', () => {
    return new HttpResponse(null, { status: 202 });
  }),
);

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
```

### Property-Based Testing (fast-check)

Instead of specifying examples, declare properties that must hold for all possible inputs:

```typescript
import fc from 'fast-check';

describe('Property-based tests', () => {
  it('sort should produce a sorted array of the same length', () => {
    fc.assert(
      fc.property(fc.array(fc.integer()), (arr) => {
        const sorted = [...arr].sort((a, b) => a - b);
        // Property 1: same length
        expect(sorted).toHaveLength(arr.length);
        // Property 2: every element is <= the next
        for (let i = 0; i < sorted.length - 1; i++) {
          expect(sorted[i]).toBeLessThanOrEqual(sorted[i + 1]);
        }
        // Property 3: same elements (permutation)
        expect(sorted.sort()).toEqual([...arr].sort());
      }),
      { numRuns: 1000 }
    );
  });

  it('JSON.parse(JSON.stringify(x)) should roundtrip', () => {
    fc.assert(
      fc.property(fc.jsonValue(), (value) => {
        expect(JSON.parse(JSON.stringify(value))).toEqual(value);
      })
    );
  });

  it('encode/decode should be inverse operations', () => {
    fc.assert(
      fc.property(fc.string(), (input) => {
        const encoded = base64Encode(input);
        const decoded = base64Decode(encoded);
        expect(decoded).toBe(input);
      })
    );
  });

  // Model-based testing — compare implementation against simple reference
  it('PriorityQueue should behave like sorted array', () => {
    const pushCmd = fc.record({
      type: fc.constant('push' as const),
      value: fc.integer({ min: -1000, max: 1000 }),
    });
    const popCmd = fc.record({ type: fc.constant('pop' as const) });

    fc.assert(
      fc.property(fc.array(fc.oneof(pushCmd, popCmd)), (commands) => {
        const pq = new PriorityQueue<number>();
        const reference: number[] = [];

        for (const cmd of commands) {
          if (cmd.type === 'push') {
            pq.push(cmd.value);
            reference.push(cmd.value);
            reference.sort((a, b) => a - b);
          } else if (reference.length > 0) {
            expect(pq.pop()).toBe(reference.shift());
          }
        }
      })
    );
  });
});
```

### Mutation Testing (Stryker)

Mutation testing verifies that your tests actually detect bugs. It introduces small mutations to your source code (changing `>` to `>=`, `+` to `-`, removing statements) and checks if tests fail. If a mutation survives (tests still pass), your tests have a blind spot.

```json
// stryker.config.json
{
  "mutator": {
    "plugins": ["@stryker-mutator/typescript-checker"],
    "excludedMutations": ["StringLiteral"]
  },
  "testRunner": "vitest",
  "reporters": ["html", "clear-text", "progress"],
  "coverageAnalysis": "perTest",
  "thresholds": { "high": 80, "low": 60, "break": 50 }
}
```

Common mutations Stryker introduces:
- **Arithmetic:** `a + b` -> `a - b`
- **Conditional boundary:** `a > b` -> `a >= b`
- **Negate conditional:** `a === b` -> `a !== b`
- **Remove statement:** Delete entire line
- **Boolean substitution:** `true` -> `false`
- **Array declaration:** `[]` -> `["Stryker was here"]`

**Interpreting results:** A mutation score of 75% means 25% of mutations survived — your tests miss those behavioral changes.

### Test Naming Conventions

```typescript
// Pattern 1: should [expected behavior] when [condition]
it('should throw NotFoundError when order does not exist', ...);
it('should return empty array when no results match filter', ...);
it('should retry 3 times when payment gateway returns 503', ...);

// Pattern 2: [unit] + [scenario] + [expected result]
it('calculateDiscount applies 20% for premium customers', ...);
it('calculateDiscount returns zero for amounts below threshold', ...);

// Pattern 3: Nested describe for context (BDD-ish)
describe('OrderService.cancel', () => {
  describe('when order is PENDING', () => {
    it('should transition to CANCELLED', ...);
    it('should refund the payment', ...);
  });
  describe('when order is SHIPPED', () => {
    it('should throw CannotCancelError', ...);
  });
});
```

**Anti-patterns in naming:**
- `it('works')` — says nothing about what is tested
- `it('test 1')` — useless for debugging failures
- `it('should call repository.save')` — tests implementation, not behavior

### Parameterized Tests

```typescript
// Vitest / Jest: it.each with table syntax
it.each([
  { input: 'racecar',  expected: true },
  { input: 'hello',    expected: false },
  { input: 'A',        expected: true },
  { input: '',         expected: true },
  { input: 'Aa',       expected: false },
])('isPalindrome("$input") should return $expected', ({ input, expected }) => {
  expect(isPalindrome(input)).toBe(expected);
});

// Tagged template literal syntax
it.each`
  a     | b     | expected
  ${1}  | ${2}  | ${3}
  ${-1} | ${1}  | ${0}
  ${0}  | ${0}  | ${0}
  ${99} | ${1}  | ${100}
`('add($a, $b) = $expected', ({ a, b, expected }) => {
  expect(add(a, b)).toBe(expected);
});

// describe.each for parameterized suites
describe.each([
  { role: 'admin',  canDelete: true,  canEdit: true },
  { role: 'editor', canDelete: false, canEdit: true },
  { role: 'viewer', canDelete: false, canEdit: false },
])('Permissions for $role', ({ role, canDelete, canEdit }) => {
  it(`canDelete should be ${canDelete}`, () => {
    const perms = getPermissions(role);
    expect(perms.canDelete).toBe(canDelete);
  });

  it(`canEdit should be ${canEdit}`, () => {
    const perms = getPermissions(role);
    expect(perms.canEdit).toBe(canEdit);
  });
});
```

---

## 7. Coverage & Quality Metrics

### Coverage Types

| Metric | What It Measures | Blind Spot |
|--------|-----------------|------------|
| **Line** | Was this line executed? | Does not detect missing logic on a line |
| **Statement** | Was this statement executed? (differs from line for multi-statement lines) | Same as line, slightly more granular |
| **Branch** | Was each branch of if/else/switch taken? | Does not test boundary conditions |
| **Function** | Was this function called? | Does not verify it was called with meaningful inputs |
| **Condition** | Was each boolean sub-expression evaluated to both true and false? | Rare in JS tooling, most tools don't track this |

```typescript
// This function has 100% line coverage but misses a critical branch
function calculateShipping(weight: number, expedited: boolean): number {
  let cost = weight * 0.5;
  if (expedited) cost *= 2;
  if (weight > 100) cost += 50; // heavy item surcharge
  return cost;
}

// This test achieves 100% line/statement coverage:
it('should calculate expedited heavy shipping', () => {
  expect(calculateShipping(150, true)).toBe(200);
  // Covers: weight * 0.5 ✓, expedited branch ✓, heavy surcharge ✓
});

// But it MISSES these branches:
// - expedited=false (what if the *= 2 is wrong for non-expedited?)
// - weight <= 100 (what if the surcharge threshold is wrong?)
// - weight=0 or negative (edge cases)
```

### Coverage Engines: Istanbul, c8, v8

| Engine | How It Works | Speed | Accuracy |
|--------|-------------|-------|----------|
| **Istanbul (babel-plugin)** | Instruments source code by injecting counters at every branch/statement during transpilation | Slow (transforms all code) | High (AST-level precision) |
| **c8** | Uses V8's built-in coverage counters via `NODE_V8_COVERAGE` env var | Fast (no instrumentation) | Good (V8-native, some edge cases with source maps) |
| **v8** (Vitest/Jest provider) | Same V8 native coverage as c8, integrated into test runner | Fast | Good |

```typescript
// vitest.config.ts — configuring coverage
export default defineConfig({
  test: {
    coverage: {
      provider: 'v8',           // or 'istanbul'
      reporter: ['text', 'html', 'lcov', 'json-summary'],
      reportsDirectory: './coverage',
      include: ['src/**/*.ts'],
      exclude: [
        'src/**/*.d.ts',
        'src/**/*.test.ts',
        'src/**/index.ts',     // Re-export files
        'src/types/**',        // Type-only files
        'src/generated/**',    // Generated code
      ],
      thresholds: {
        branches: 80,
        functions: 85,
        lines: 85,
        statements: 85,
      },
    },
  },
});
```

### Why 100% Coverage Is a Trap

100% code coverage creates a false sense of security and leads to harmful behaviors:

1. **Coverage measures execution, not correctness.** A test can execute every line without asserting anything meaningful:

```typescript
// 100% coverage, 0% confidence
it('should not crash', () => {
  processOrder(testOrder); // No assertions — just checks it doesn't throw
});
```

2. **Chasing 100% forces testing trivial code.** Writing tests for getters, DTO mappings, and framework boilerplate wastes time and creates maintenance burden.

3. **Coverage does not test boundary conditions.** As shown above, 100% line coverage can miss critical branches and edge cases.

4. **Coverage cannot detect missing tests.** If a requirement is unimplemented, no test exists for it, and coverage cannot tell you that.

5. **Goodhart's Law.** When a metric becomes a target, it ceases to be a useful metric. Developers write low-value tests to hit the number.

### Meaningful Coverage Targets

| Code Category | Recommended Target | Rationale |
|--------------|-------------------|-----------|
| Core business logic | 90%+ branch coverage | Highest risk, highest value |
| API controllers/routes | 80%+ line coverage | Integration tests cover most paths |
| Database repositories | 70%+ (integration tests) | SQL logic tested against real DB |
| Utility functions | 95%+ | Pure functions, easy to test exhaustively |
| Generated code | Exclude from coverage | Not your code |
| Config/bootstrapping | Exclude or low target | Trivial, tested by app startup |
| DTOs/types | N/A (TypeScript handles) | Static analysis, not runtime testing |

### Coverage in CI Pipelines

```yaml
# .github/workflows/test.yml
jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with:
          node-version: '22'
          cache: 'npm'
      - run: npm ci
      - run: npx vitest --coverage --reporter=junit --outputFile=test-results.xml
      - name: Check coverage thresholds
        run: |
          # vitest exits with non-zero if thresholds are not met
          # Alternative: parse json-summary and fail explicitly
          node -e "
            const summary = require('./coverage/coverage-summary.json');
            const { lines, branches } = summary.total;
            if (lines.pct < 80 || branches.pct < 75) {
              console.error('Coverage below threshold');
              process.exit(1);
            }
          "
      - name: Upload coverage to Codecov
        uses: codecov/codecov-action@v4
        with:
          files: ./coverage/lcov.info
          fail_ci_if_error: true
```

### What Coverage Misses

Even with high coverage, these bugs slip through:
- **Off-by-one errors** in boundary conditions (covered line, wrong value)
- **Race conditions** in concurrent code (deterministic tests cannot reproduce)
- **Integration contract mismatches** (both sides covered but incompatible)
- **Performance regressions** (code works but 10x slower)
- **Security vulnerabilities** (SQL injection in a covered query)
- **Missing error handling** (happy path covered, error paths not)
- **Environment-specific behavior** (works on Linux, fails on macOS)

This is why mutation testing (section 6) and property-based testing complement coverage metrics.

---

## 8. Testing Async Code

### Testing Promises and async/await

```typescript
// Return the promise (Jest/Vitest will wait for it)
it('should fetch user', () => {
  return fetchUser('123').then(user => {
    expect(user.name).toBe('Alice');
  });
});

// async/await — preferred style
it('should fetch user', async () => {
  const user = await fetchUser('123');
  expect(user.name).toBe('Alice');
});

// Testing rejected promises
it('should reject with NotFoundError', async () => {
  await expect(fetchUser('nonexistent')).rejects.toThrow('Not found');
});

// More specific rejection assertions
it('should reject with typed error', async () => {
  await expect(fetchUser('nonexistent')).rejects.toMatchObject({
    code: 'NOT_FOUND',
    message: expect.stringContaining('nonexistent'),
    statusCode: 404,
  });
});

// DANGER: forgetting to await/return a promise
it('should fail but actually passes (BUG IN TEST)', () => {
  // This assertion runs AFTER the test completes — the test always passes
  fetchUser('nonexistent').catch(err => {
    expect(err.message).toBe('Not found'); // Never actually checked!
  });
});

// FIX: use expect.assertions() to guard against this
it('should reject', async () => {
  expect.assertions(1); // Test fails if exactly 1 assertion is not made
  try {
    await fetchUser('nonexistent');
  } catch (err) {
    expect(err.message).toBe('Not found');
  }
});
```

### Fake Timers

```typescript
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

describe('Fake timers', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('should debounce function calls', async () => {
    const callback = vi.fn();
    const debounced = debounce(callback, 300);

    debounced('a');
    debounced('b');
    debounced('c');

    expect(callback).not.toHaveBeenCalled();

    vi.advanceTimersByTime(300);

    expect(callback).toHaveBeenCalledOnce();
    expect(callback).toHaveBeenCalledWith('c'); // Only last call fires
  });

  it('should handle setInterval-based polling', async () => {
    const onPoll = vi.fn().mockResolvedValue({ status: 'processing' });
    startPolling(onPoll, 1000);

    await vi.advanceTimersByTimeAsync(3500);

    expect(onPoll).toHaveBeenCalledTimes(3); // At 1s, 2s, 3s
  });

  it('should expire cache entries', () => {
    const cache = new TTLCache<string, number>(5000); // 5s TTL
    cache.set('key', 42);

    expect(cache.get('key')).toBe(42);

    vi.advanceTimersByTime(4999);
    expect(cache.get('key')).toBe(42); // Still valid

    vi.advanceTimersByTime(1);
    expect(cache.get('key')).toBeUndefined(); // Expired
  });

  it('should handle Date.now() with fake timers', () => {
    vi.setSystemTime(new Date('2025-01-01T00:00:00Z'));

    const token = createExpiringToken();
    expect(token.issuedAt).toEqual(new Date('2025-01-01T00:00:00Z'));

    vi.advanceTimersByTime(3600_000); // Advance 1 hour
    expect(Date.now()).toBe(new Date('2025-01-01T01:00:00Z').getTime());
  });
});

// IMPORTANT: vi.advanceTimersByTimeAsync() vs vi.advanceTimersByTime()
// Use the async variant when timer callbacks trigger promises (e.g., setTimeout -> fetch)
// The sync variant will not flush microtask queues, causing subtle bugs
```

### Testing Event Emitters

```typescript
import { EventEmitter } from 'node:events';
import { describe, it, expect, vi } from 'vitest';
import { once } from 'node:events';

describe('Event Emitters', () => {
  it('should emit events in order', () => {
    const emitter = new EventEmitter();
    const handler = vi.fn();

    emitter.on('data', handler);
    emitter.emit('data', { id: 1 });
    emitter.emit('data', { id: 2 });

    expect(handler).toHaveBeenCalledTimes(2);
    expect(handler).toHaveBeenNthCalledWith(1, { id: 1 });
    expect(handler).toHaveBeenNthCalledWith(2, { id: 2 });
  });

  it('should wait for async event with once()', async () => {
    const emitter = new EventEmitter();

    // Schedule emission
    setTimeout(() => emitter.emit('ready', { port: 3000 }), 100);

    const [event] = await once(emitter, 'ready');
    expect(event).toEqual({ port: 3000 });
  });

  it('should test error events', async () => {
    const emitter = new EventEmitter();

    setTimeout(() => emitter.emit('error', new Error('connection lost')), 50);

    await expect(once(emitter, 'error')).resolves.toEqual([
      expect.objectContaining({ message: 'connection lost' }),
    ]);
  });

  it('should test event-driven service', async () => {
    const service = new OrderEventProcessor();
    const completedOrders: string[] = [];

    service.on('order:completed', (orderId: string) => {
      completedOrders.push(orderId);
    });

    await service.process({ id: 'ORD-001', action: 'complete' });
    await service.process({ id: 'ORD-002', action: 'complete' });

    expect(completedOrders).toEqual(['ORD-001', 'ORD-002']);
  });
});
```

### Testing Streams

```typescript
import { Readable, Transform, pipeline } from 'node:stream';
import { describe, it, expect } from 'vitest';
import { text } from 'node:stream/consumers';

describe('Streams', () => {
  it('should test readable stream', async () => {
    const readable = Readable.from(['hello', ' ', 'world']);
    const result = await text(readable);
    expect(result).toBe('hello world');
  });

  it('should test transform stream', async () => {
    const uppercase = new Transform({
      transform(chunk, _encoding, callback) {
        callback(null, chunk.toString().toUpperCase());
      },
    });

    const input = Readable.from(['hello world']);
    const output = input.pipe(uppercase);
    const result = await text(output);
    expect(result).toBe('HELLO WORLD');
  });

  it('should test CSV parser stream', async () => {
    const csvData = 'name,age\nAlice,30\nBob,25\n';
    const input = Readable.from([csvData]);
    const rows: any[] = [];

    const parser = createCsvParser(); // Your stream-based CSV parser

    await new Promise<void>((resolve, reject) => {
      pipeline(input, parser, (err) => {
        if (err) reject(err);
        else resolve();
      });
      parser.on('data', (row) => rows.push(row));
    });

    expect(rows).toEqual([
      { name: 'Alice', age: '30' },
      { name: 'Bob', age: '25' },
    ]);
  });

  it('should test stream error handling', async () => {
    const failingStream = new Readable({
      read() {
        this.destroy(new Error('disk read error'));
      },
    });

    const chunks: Buffer[] = [];
    await expect(
      new Promise((resolve, reject) => {
        failingStream.on('data', (chunk) => chunks.push(chunk));
        failingStream.on('error', reject);
        failingStream.on('end', resolve);
      })
    ).rejects.toThrow('disk read error');
  });

  it('should test backpressure handling', async () => {
    let writeCount = 0;
    const slowConsumer = new Transform({
      highWaterMark: 2,
      async transform(chunk, _encoding, callback) {
        writeCount++;
        await new Promise((r) => setTimeout(r, 10));
        callback(null, chunk);
      },
    });

    const data = Array.from({ length: 100 }, (_, i) => `chunk-${i}\n`);
    const input = Readable.from(data);

    await text(input.pipe(slowConsumer));
    expect(writeCount).toBe(100); // All chunks processed despite backpressure
  });
});
```

### Testing WebSocket Connections

```typescript
import { WebSocketServer, WebSocket } from 'ws';
import { describe, it, expect, beforeAll, afterAll } from 'vitest';

describe('WebSocket handler', () => {
  let wss: WebSocketServer;
  let serverPort: number;

  beforeAll(async () => {
    wss = new WebSocketServer({ port: 0 }); // Random available port
    serverPort = (wss.address() as any).port;

    wss.on('connection', (ws) => {
      ws.on('message', (data) => {
        const message = JSON.parse(data.toString());
        if (message.type === 'ping') {
          ws.send(JSON.stringify({ type: 'pong', timestamp: Date.now() }));
        }
        if (message.type === 'subscribe') {
          ws.send(JSON.stringify({ type: 'subscribed', channel: message.channel }));
          // Simulate real-time updates
          const interval = setInterval(() => {
            ws.send(JSON.stringify({ type: 'update', channel: message.channel, value: Math.random() }));
          }, 100);
          ws.on('close', () => clearInterval(interval));
        }
      });
    });
  });

  afterAll(() => {
    wss.close();
  });

  function connectClient(): Promise<WebSocket> {
    return new Promise((resolve) => {
      const ws = new WebSocket(`ws://localhost:${serverPort}`);
      ws.on('open', () => resolve(ws));
    });
  }

  function waitForMessage(ws: WebSocket): Promise<any> {
    return new Promise((resolve) => {
      ws.once('message', (data) => resolve(JSON.parse(data.toString())));
    });
  }

  it('should respond to ping with pong', async () => {
    const ws = await connectClient();
    ws.send(JSON.stringify({ type: 'ping' }));

    const response = await waitForMessage(ws);
    expect(response.type).toBe('pong');
    expect(response.timestamp).toBeTypeOf('number');

    ws.close();
  });

  it('should handle subscriptions', async () => {
    const ws = await connectClient();
    ws.send(JSON.stringify({ type: 'subscribe', channel: 'prices' }));

    const ack = await waitForMessage(ws);
    expect(ack).toMatchObject({ type: 'subscribed', channel: 'prices' });

    const update = await waitForMessage(ws);
    expect(update.type).toBe('update');
    expect(update.channel).toBe('prices');

    ws.close();
  });
});
```

### Timeout Handling in Tests

```typescript
// Vitest: per-test timeout
it('should complete within 5 seconds', async () => {
  const result = await longRunningOperation();
  expect(result).toBeDefined();
}, 5000); // 5s timeout (second argument)

// Suite-level timeout
describe('slow integration tests', () => {
  // Vitest config: test.testTimeout
  // Or use beforeAll with a long timeout for container startup
  beforeAll(async () => {
    await startContainers();
  }, 60_000); // 60s for container startup

  it('should query database', async () => {
    // Inherits default timeout (5s)
  });
});

// Testing that YOUR code handles timeouts correctly
it('should abort fetch after timeout', async () => {
  const server = setupServer(
    http.get('/slow', async () => {
      await new Promise(r => setTimeout(r, 10_000)); // Server takes 10s
      return HttpResponse.json({ data: 'too late' });
    })
  );
  server.listen();

  await expect(
    fetchWithTimeout('/slow', { timeout: 100 })
  ).rejects.toThrow('Request timed out');

  server.close();
});

// Testing AbortController integration
it('should respect AbortSignal', async () => {
  const controller = new AbortController();

  const promise = longRunningTask({ signal: controller.signal });

  // Abort after 50ms
  setTimeout(() => controller.abort(), 50);

  await expect(promise).rejects.toThrow('AbortError');
});
```

### Flaky Test Diagnosis

Flaky tests pass and fail non-deterministically. They erode trust in the test suite and slow CI.

**Common causes and fixes:**

| Cause | Symptom | Fix |
|-------|---------|-----|
| Shared mutable state | Fails only when run with other tests | Isolate state per test, use `beforeEach` reset |
| Time-dependent | Fails near midnight, DST, or on slow CI | Fake timers, inject clock |
| Port conflicts | Fails in parallel | Use port 0 (random), `getAvailablePort()` |
| Race conditions | Fails intermittently | `await` all async ops, avoid polling without limits |
| Network dependency | Fails on flaky network | MSW / nock for HTTP, no real network in unit tests |
| File system state | Fails on second run | Use `tmp` directories, clean up in `afterEach` |
| Non-deterministic order | Depends on previous test state | Run with `--randomize` flag to detect |
| Floating promises | Sometimes passes, sometimes doesn't | `expect.assertions(n)`, await all promises |

**Diagnosing strategy:**

```bash
# Run test in isolation — if it passes, shared state is the cause
npx vitest run src/services/order.test.ts

# Run multiple times to reproduce
npx vitest run --reporter=verbose --retry=0 --repeat=50

# Randomize test order to find ordering dependencies
npx vitest run --sequence.shuffle

# Run with verbose logging
DEBUG=* npx vitest run --reporter=verbose
```

```typescript
// Defensive pattern: wait for condition instead of fixed delay
async function waitFor(
  condition: () => boolean | Promise<boolean>,
  { timeout = 5000, interval = 50 } = {}
): Promise<void> {
  const start = Date.now();
  while (Date.now() - start < timeout) {
    if (await condition()) return;
    await new Promise(r => setTimeout(r, interval));
  }
  throw new Error(`Condition not met within ${timeout}ms`);
}

it('should eventually process message', async () => {
  await publishMessage({ type: 'order.created', orderId: '123' });

  // Instead of: await new Promise(r => setTimeout(r, 2000));
  await waitFor(async () => {
    const order = await db.orders.findById('123');
    return order?.status === 'PROCESSED';
  }, { timeout: 5000 });

  const order = await db.orders.findById('123');
  expect(order!.status).toBe('PROCESSED');
});
```

**Quarantine pattern:** Move flaky tests to a separate suite that runs but does not block CI, while you investigate:

```typescript
// vitest.config.ts
export default defineConfig({
  test: {
    include: ['src/**/*.test.ts'],
    exclude: ['src/**/*.flaky.test.ts'], // Excluded from main run
  },
});

// Separate CI job runs flaky tests with retries
// npx vitest run --include='src/**/*.flaky.test.ts' --retry=3
```

---
