# TypeScript Type System — Senior Engineer Interview Preparation

---

## 1. Structural vs Nominal Typing

### TypeScript's Structural Type System

TypeScript uses **structural typing** (aka "duck typing"): two types are compatible if their members are compatible. The name of the type does not matter — only the shape.

```typescript
interface Point {
  x: number;
  y: number;
}

interface Coordinate {
  x: number;
  y: number;
}

const p: Point = { x: 1, y: 2 };
const c: Coordinate = p;  // OK — same shape, different names
```

This is fundamentally different from Java/C# which use **nominal typing** — two types are compatible only if they explicitly declare a relationship (via `extends` or `implements`).

```
Structural Typing (TypeScript)          Nominal Typing (Java)
┌─────────────────────────┐             ┌─────────────────────────┐
│ Compatible if shapes    │             │ Compatible only if      │
│ match, regardless of    │             │ explicitly related via  │
│ type name               │             │ extends / implements    │
│                         │             │                         │
│ Point ≈ Coordinate      │             │ Point ≠ Coordinate      │
│ (same shape = same type)│             │ (unless one extends the │
│                         │             │  other)                 │
└─────────────────────────┘             └─────────────────────────┘
```

### Duck Typing Implications

Structural typing means any object satisfying the shape is assignable. This creates subtle bugs when types have identical shapes but different semantic meanings:

```typescript
interface UserId { value: string; }
interface OrderId { value: string; }

function cancelOrder(orderId: OrderId): void { /* ... */ }

const userId: UserId = { value: "user-123" };
cancelOrder(userId);  // Compiles! Both have { value: string }
// This is a SEMANTIC BUG — TypeScript cannot catch it
```

### Simulating Nominal Types (Branded Types)

**Technique 1: Intersection with unique symbol**

```typescript
declare const __brand: unique symbol;

type Brand<T, B extends string> = T & { readonly [__brand]: B };

type UserId = Brand<string, "UserId">;
type OrderId = Brand<string, "OrderId">;

function createUserId(id: string): UserId {
  return id as UserId;
}

function createOrderId(id: string): OrderId {
  return id as OrderId;
}

function cancelOrder(orderId: OrderId): void { /* ... */ }

const userId = createUserId("user-123");
const orderId = createOrderId("order-456");

cancelOrder(orderId);  // OK
cancelOrder(userId);   // ERROR: UserId is not assignable to OrderId
```

**Technique 2: Private field brand (class-based)**

```typescript
class UserId {
  private readonly __userId = true;  // prevents structural match
  constructor(public readonly value: string) {}
}

class OrderId {
  private readonly __orderId = true;
  constructor(public readonly value: string) {}
}

// Now these are nominally distinct — private fields break structural equivalence
```

**Why this works**: TypeScript checks private members by declaration site, not structurally. Two classes with different private fields are incompatible even if the visible shape is the same.

### Comparison Table

| Feature | TypeScript (Structural) | Java/C# (Nominal) |
|---------|------------------------|--------------------|
| Compatibility rule | Shape match | Explicit relationship |
| Interface implementation | Implicit (shape fits) | Explicit (`implements`) |
| Semantic safety | Weak (same shape = same type) | Strong (names matter) |
| Flexibility | High (easy duck typing) | Lower (requires hierarchy) |
| Refactoring risk | Higher (rename doesn't break) | Lower (compiler catches) |
| Runtime overhead | None (types erased) | Types exist at runtime |

### Excess Property Checking

A notable exception to pure structural typing: TypeScript applies **excess property checking** on object literals assigned directly to typed positions.

```typescript
interface Options {
  width: number;
  height: number;
}

// Direct literal — excess property caught
const opts: Options = { width: 100, height: 50, colour: "red" };
// Error: Object literal may only specify known properties

// Via intermediate variable — no excess check
const raw = { width: 100, height: 50, colour: "red" };
const opts2: Options = raw;  // OK! Structural typing wins
```

This is a pragmatic compromise: object literals are usually a mistake if they have extra properties, but variables may have legitimately richer types.

---

## 2. Generics

### Generic Functions, Classes, Interfaces

```typescript
// Generic function
function identity<T>(value: T): T {
  return value;
}

// Generic interface
interface Repository<T> {
  findById(id: string): T | undefined;
  save(entity: T): void;
}

// Generic class
class TypedQueue<T> {
  private items: T[] = [];
  enqueue(item: T): void { this.items.push(item); }
  dequeue(): T | undefined { return this.items.shift(); }
}
```

### Generic Constraints (`extends`)

Constraints restrict what types can be used as a type argument:

```typescript
// Basic constraint
function getLength<T extends { length: number }>(item: T): number {
  return item.length;
}

getLength("hello");       // OK, string has .length
getLength([1, 2, 3]);     // OK, array has .length
getLength(42);             // Error: number has no .length

// Constraint with keyof
function getProperty<T, K extends keyof T>(obj: T, key: K): T[K] {
  return obj[key];
}

const user = { name: "Alice", age: 30 };
getProperty(user, "name");  // OK, type is string
getProperty(user, "email"); // Error: "email" not in keyof typeof user

// Multiple constraints with intersection
function merge<T extends object, U extends object>(a: T, b: U): T & U {
  return { ...a, ...b };
}
```

### Default Type Parameters

```typescript
interface ApiResponse<TData = unknown, TError = Error> {
  data?: TData;
  error?: TError;
  status: number;
}

// Uses defaults
const response: ApiResponse = { status: 200 };

// Override first, keep second default
const typedResponse: ApiResponse<User> = { data: user, status: 200 };
```

### Generic Inference and When It Fails

TypeScript infers generic types from usage, but this can fail in several ways:

```typescript
// WORKS — T inferred from argument
function wrap<T>(value: T): { wrapped: T } {
  return { wrapped: value };
}
const result = wrap(42);  // T inferred as number

// FAILS — T cannot be inferred from return type alone
function create<T>(): T {
  return {} as T;  // Caller must explicitly specify T
}
const user = create<User>();  // Must pass type argument

// FAILS — conflicting inference sites
function combine<T>(a: T, b: T): T {
  return Math.random() > 0.5 ? a : b;
}
combine(1, "hello");  // Error: T is number | string, conflicting

// FIX — use union explicitly or two type params
function combine2<T, U>(a: T, b: U): T | U {
  return Math.random() > 0.5 ? a : b;
}
```

**`NoInfer` utility type (TS 5.4+)**: Prevents a position from contributing to inference.

```typescript
function createFSM<S extends string>(
  initial: NoInfer<S>,
  states: S[]
): void { /* ... */ }

// Without NoInfer, initial could widen S
// With NoInfer, S is inferred only from states
createFSM("loading", ["idle", "loading", "done"]);  // OK
createFSM("typo", ["idle", "loading", "done"]);      // Error!
```

### Variance: Covariance, Contravariance, Invariance

```
                Variance in TypeScript
┌────────────────────────────────────────────────────────┐
│                                                        │
│  Covariant (output position):                          │
│    If Dog extends Animal, then                         │
│    Producer<Dog> extends Producer<Animal>               │
│                                                        │
│  Contravariant (input position):                       │
│    If Dog extends Animal, then                         │
│    Consumer<Animal> extends Consumer<Dog>               │
│                                                        │
│  Invariant (both positions):                           │
│    Array<Dog> is NOT assignable to Array<Animal>        │
│    (in strict mode, for mutable containers)            │
│                                                        │
└────────────────────────────────────────────────────────┘
```

```typescript
// Covariant: T in output position
interface Producer<T> {
  produce(): T;
}

// Contravariant: T in input position
interface Consumer<T> {
  consume(value: T): void;
}

// Explicit variance annotations (TS 4.7+)
interface ReadonlyBox<out T> {   // Covariant
  get(): T;
}

interface WriteBox<in T> {       // Contravariant
  set(value: T): void;
}

interface MutableBox<in out T> { // Invariant
  get(): T;
  set(value: T): void;
}
```

**Gotcha**: TypeScript is **unsound by design** with arrays — `Dog[]` is assignable to `Animal[]` even though arrays are mutable. This is a pragmatic choice for ergonomics.

```typescript
class Animal { name = "animal"; }
class Dog extends Animal { bark() { console.log("woof"); } }

const dogs: Dog[] = [new Dog()];
const animals: Animal[] = dogs;  // Allowed! But unsound:
animals.push(new Animal());      // Pushes a non-Dog into dogs
dogs[1].bark();                   // Runtime error!
```

### `infer` Keyword in Conditional Types

`infer` declares a type variable inside a conditional type's `extends` clause, extracting a part of a type:

```typescript
// Extract return type
type MyReturnType<T> = T extends (...args: any[]) => infer R ? R : never;

// Extract promise value
type Unpromise<T> = T extends Promise<infer U> ? Unpromise<U> : T;

// Extract array element type
type ElementType<T> = T extends (infer E)[] ? E : never;

// Extract first argument
type FirstArg<T> = T extends (first: infer F, ...rest: any[]) => any ? F : never;

// Multiple infer positions — same variable unifies to union
type Values<T> = T extends { a: infer V; b: infer V } ? V : never;
type Test = Values<{ a: string; b: number }>;  // string | number

// Infer in template literal
type ExtractRouteParam<T> = T extends `${string}:${infer Param}/${infer Rest}`
  ? Param | ExtractRouteParam<Rest>
  : T extends `${string}:${infer Param}`
    ? Param
    : never;

type Params = ExtractRouteParam<"/users/:userId/posts/:postId">;
// "userId" | "postId"
```

---

## 3. Advanced Type Utilities

### Conditional Types

Conditional types select one of two types based on a condition, and they **distribute** over union types:

```typescript
type IsString<T> = T extends string ? true : false;

type A = IsString<string>;           // true
type B = IsString<number>;           // false
type C = IsString<string | number>;  // true | false = boolean (distributed!)

// Prevent distribution with wrapping
type IsStringExact<T> = [T] extends [string] ? true : false;
type D = IsStringExact<string | number>;  // false (no distribution)
```

### Mapped Types

Transform every property of a type:

```typescript
// Basic mapped type
type Readonly<T> = { readonly [K in keyof T]: T[K] };
type Optional<T> = { [K in keyof T]?: T[K] };

// With key remapping (TS 4.1+)
type Getters<T> = {
  [K in keyof T as `get${Capitalize<string & K>}`]: () => T[K];
};

interface Person { name: string; age: number; }
type PersonGetters = Getters<Person>;
// { getName: () => string; getAge: () => number }

// Filtering keys via remapping
type OnlyStrings<T> = {
  [K in keyof T as T[K] extends string ? K : never]: T[K];
};

type StringFields = OnlyStrings<Person>;
// { name: string }
```

### Template Literal Types

String manipulation at the type level:

```typescript
type EventName = `on${Capitalize<"click" | "focus" | "blur">}`;
// "onClick" | "onFocus" | "onBlur"

type HTTPMethod = "GET" | "POST" | "PUT" | "DELETE";
type Endpoint = `/api/${string}`;
type Route = `${HTTPMethod} ${Endpoint}`;
// "GET /api/${string}" | "POST /api/${string}" | ...

// Pattern matching with template literals
type ParseInt<T> = T extends `${infer N extends number}` ? N : never;
type Num = ParseInt<"42">;  // 42 (number literal type)

// Splitting strings
type Split<S extends string, Sep extends string> =
  S extends `${infer Head}${Sep}${infer Tail}`
    ? [Head, ...Split<Tail, Sep>]
    : S extends ""
      ? []
      : [S];

type Parts = Split<"a.b.c", ".">;  // ["a", "b", "c"]
```

### Recursive Types

```typescript
// Deep readonly
type DeepReadonly<T> =
  T extends (infer E)[]
    ? ReadonlyArray<DeepReadonly<E>>
    : T extends object
      ? { readonly [K in keyof T]: DeepReadonly<T[K]> }
      : T;

// Deep partial
type DeepPartial<T> =
  T extends object
    ? { [K in keyof T]?: DeepPartial<T[K]> }
    : T;

// JSON type
type JSONValue =
  | string
  | number
  | boolean
  | null
  | JSONValue[]
  | { [key: string]: JSONValue };
```

### Built-in Utility Types Deep Dive

| Utility | Definition | Use Case |
|---------|-----------|----------|
| `Partial<T>` | All properties optional | Update/patch payloads |
| `Required<T>` | All properties required | Ensure completeness |
| `Readonly<T>` | All properties readonly | Immutable configs |
| `Pick<T, K>` | Select specific properties | API response subsets |
| `Omit<T, K>` | Exclude specific properties | Remove internal fields |
| `Record<K, V>` | Object type with key K, value V | Maps/dictionaries |
| `Extract<T, U>` | Members of T assignable to U | Filter union types |
| `Exclude<T, U>` | Members of T not assignable to U | Remove from unions |
| `ReturnType<T>` | Return type of function T | Derive from functions |
| `Parameters<T>` | Tuple of function parameters | Wrapping functions |
| `Awaited<T>` | Unwraps Promise recursively | Async return types |
| `NonNullable<T>` | Removes null and undefined | Strict null handling |
| `NoInfer<T>` | Blocks inference at position | Control inference sites |

```typescript
// Extract and Exclude — operating on unions
type Animals = "cat" | "dog" | "fish" | "bird";
type Mammals = Extract<Animals, "cat" | "dog" | "whale">;  // "cat" | "dog"
type NonMammals = Exclude<Animals, "cat" | "dog">;          // "fish" | "bird"

// ReturnType and Parameters — reflecting on functions
function fetchUser(id: string, options?: { cache: boolean }): Promise<User> {
  /* ... */
}

type FetchReturn = ReturnType<typeof fetchUser>;      // Promise<User>
type FetchParams = Parameters<typeof fetchUser>;      // [string, { cache: boolean }?]
type FetchResolved = Awaited<ReturnType<typeof fetchUser>>;  // User

// Record for index-like objects
type StatusMap = Record<"success" | "error" | "pending", { message: string }>;

// Omit for removing sensitive fields
type PublicUser = Omit<User, "password" | "ssn">;
```

### Custom Utility Type Examples

```typescript
// PathOf — dot-notation paths through an object
type PathOf<T, Prefix extends string = ""> = T extends object
  ? {
      [K in keyof T & string]: T[K] extends object
        ? PathOf<T[K], `${Prefix}${K}.`> | `${Prefix}${K}`
        : `${Prefix}${K}`;
    }[keyof T & string]
  : never;

interface Config {
  db: { host: string; port: number; ssl: { enabled: boolean } };
  app: { name: string };
}

type ConfigPath = PathOf<Config>;
// "db" | "db.host" | "db.port" | "db.ssl" | "db.ssl.enabled" | "app" | "app.name"

// Get — type-safe deep property access
type Get<T, Path extends string> =
  Path extends `${infer Head}.${infer Tail}`
    ? Head extends keyof T
      ? Get<T[Head], Tail>
      : never
    : Path extends keyof T
      ? T[Path]
      : never;

type SSLEnabled = Get<Config, "db.ssl.enabled">;  // boolean

// MutableRequired — remove readonly AND make required
type Mutable<T> = { -readonly [K in keyof T]: T[K] };
type MutableRequired<T> = Mutable<Required<T>>;
```

---

## 4. Type Guards & Narrowing

### Built-in Narrowing

```typescript
function process(value: string | number | boolean) {
  if (typeof value === "string") {
    // value is string here
    console.log(value.toUpperCase());
  } else if (typeof value === "number") {
    // value is number here
    console.log(value.toFixed(2));
  } else {
    // value is boolean here
    console.log(value ? "yes" : "no");
  }
}

// instanceof
function formatDate(date: string | Date) {
  if (date instanceof Date) {
    return date.toISOString();   // date is Date
  }
  return new Date(date).toISOString();  // date is string
}

// "in" operator
interface Fish { swim(): void; }
interface Bird { fly(): void; }

function move(animal: Fish | Bird) {
  if ("swim" in animal) {
    animal.swim();  // animal is Fish
  } else {
    animal.fly();   // animal is Bird
  }
}
```

### User-Defined Type Guards (`is` keyword)

```typescript
// The `is` keyword in the return type tells TypeScript to narrow
function isString(value: unknown): value is string {
  return typeof value === "string";
}

// More complex type guard
interface ApiError {
  code: number;
  message: string;
  details?: Record<string, unknown>;
}

function isApiError(error: unknown): error is ApiError {
  return (
    typeof error === "object" &&
    error !== null &&
    "code" in error &&
    typeof (error as ApiError).code === "number" &&
    "message" in error &&
    typeof (error as ApiError).message === "string"
  );
}

// Usage
try {
  await fetchData();
} catch (error) {
  if (isApiError(error)) {
    console.log(error.code);     // TypeScript knows error is ApiError
    console.log(error.message);  // fully typed
  }
}

// Type guard for arrays
function isNonEmpty<T>(arr: T[]): arr is [T, ...T[]] {
  return arr.length > 0;
}

const items: string[] = getItems();
if (isNonEmpty(items)) {
  console.log(items[0].toUpperCase());  // Safe — guaranteed at least one element
}
```

**Warning**: User-defined type guards are **unchecked by the compiler**. If your guard is wrong, TypeScript trusts you and narrows incorrectly:

```typescript
// BUG — TypeScript believes you unconditionally
function isString(value: unknown): value is string {
  return true;  // ALWAYS returns true — TypeScript won't catch this
}

const num = 42;
if (isString(num)) {
  num.toUpperCase();  // No compiler error, but runtime crash!
}
```

### Discriminated Unions (Tagged Unions)

The most powerful pattern for modeling state machines and complex domains:

```typescript
// Each variant has a shared literal "tag" property
type Result<T, E = Error> =
  | { success: true; data: T }
  | { success: false; error: E };

function handleResult(result: Result<User>) {
  if (result.success) {
    console.log(result.data.name);  // TypeScript knows data exists
  } else {
    console.log(result.error.message);  // TypeScript knows error exists
  }
}

// State machine with discriminated union
type RequestState<T> =
  | { status: "idle" }
  | { status: "loading" }
  | { status: "success"; data: T }
  | { status: "error"; error: Error; retryCount: number };

function renderState(state: RequestState<User>) {
  switch (state.status) {
    case "idle":
      return "Ready";
    case "loading":
      return "Loading...";
    case "success":
      return `Hello ${state.data.name}`;  // data available
    case "error":
      return `Error: ${state.error.message} (attempt ${state.retryCount})`;
  }
}
```

### Assertion Functions (`asserts`)

```typescript
// Assertion function — throws if condition is false, narrows if true
function assertDefined<T>(
  value: T | null | undefined,
  message?: string
): asserts value is T {
  if (value === null || value === undefined) {
    throw new Error(message ?? "Value is not defined");
  }
}

// Usage — narrows after the call (no if-block needed)
function processUser(user: User | null) {
  assertDefined(user, "User must exist");
  // From here, user is User (not null)
  console.log(user.name);
}

// Assert with condition
function assert(condition: boolean, msg: string): asserts condition {
  if (!condition) throw new Error(msg);
}

function process(value: string | number) {
  assert(typeof value === "string", "Expected string");
  // value is string from here
  console.log(value.toUpperCase());
}
```

### Exhaustive Checking with `never`

```typescript
type Shape =
  | { kind: "circle"; radius: number }
  | { kind: "square"; side: number }
  | { kind: "triangle"; base: number; height: number };

function area(shape: Shape): number {
  switch (shape.kind) {
    case "circle":
      return Math.PI * shape.radius ** 2;
    case "square":
      return shape.side ** 2;
    case "triangle":
      return (shape.base * shape.height) / 2;
    default:
      // If you add a new shape variant and forget a case,
      // this line will produce a compile error
      const _exhaustive: never = shape;
      return _exhaustive;
  }
}

// Helper function approach
function assertNever(x: never): never {
  throw new Error(`Unexpected value: ${JSON.stringify(x)}`);
}
```

### `satisfies` Operator (TS 4.9+)

`satisfies` validates that an expression matches a type WITHOUT widening or changing the inferred type:

```typescript
type Color = "red" | "green" | "blue";
type ColorMap = Record<string, Color | [number, number, number]>;

// With `as` — loses specific literal types
const colorsAs = {
  red: "red",
  green: [0, 255, 0],
} as ColorMap;
// colorsAs.red is Color | [number, number, number] — too wide

// With annotation — same problem
const colorsAnnotated: ColorMap = {
  red: "red",
  green: [0, 255, 0],
};
// colorsAnnotated.red is Color | [number, number, number]

// With satisfies — validates AND preserves narrow types
const colors = {
  red: "red",
  green: [0, 255, 0],
} satisfies ColorMap;
// colors.red is "red" (literal type preserved!)
// colors.green is [number, number, number] (tuple preserved!)

colors.red.toUpperCase();     // OK — TypeScript knows it's a string
colors.green.map(x => x);    // OK — TypeScript knows it's a tuple
```

**When to use `satisfies` vs `as` vs annotation**:

| Technique | Validates | Narrows | Preserves literal | Safe |
|-----------|-----------|---------|-------------------|------|
| `: Type` annotation | Yes | No (widens) | No | Yes |
| `as Type` assertion | No (unsafe) | Forces type | No | No |
| `satisfies Type` | Yes | No (keeps original) | Yes | Yes |
| `as const` | No | Yes (narrows maximally) | Yes | Yes |

---

## 5. Declaration Merging & Module Augmentation

### Interface Merging

When two interfaces with the same name are declared in the same scope, TypeScript merges them:

```typescript
interface User {
  name: string;
}

interface User {
  email: string;
}

// Merged result:
// interface User { name: string; email: string; }
const user: User = { name: "Alice", email: "alice@example.com" };
```

**Merge rules**:
- Non-function members must have identical types if duplicated
- Function members are overloaded (later declarations get higher priority)
- This is how libraries like Express extend `Request` with custom properties

```typescript
// Extending Express Request (common real-world pattern)
declare global {
  namespace Express {
    interface Request {
      userId?: string;
      correlationId?: string;
    }
  }
}

// Now req.userId is typed in all route handlers
app.get("/profile", (req, res) => {
  console.log(req.userId);  // string | undefined
});
```

### Namespace Merging

Namespaces merge with classes, functions, and enums to add static properties:

```typescript
// Namespace + Class merging
class Album {
  label: Album.AlbumLabel;
  constructor(label: Album.AlbumLabel) {
    this.label = label;
  }
}

namespace Album {
  export interface AlbumLabel {
    name: string;
    country: string;
  }
  export const DEFAULT_LABEL: AlbumLabel = { name: "Unknown", country: "US" };
}

const album = new Album(Album.DEFAULT_LABEL);

// Namespace + Function merging (adding properties to functions)
function buildUrl(path: string): string {
  return `${buildUrl.BASE_URL}${path}`;
}

namespace buildUrl {
  export let BASE_URL = "https://api.example.com";
}

buildUrl.BASE_URL = "https://staging.example.com";
buildUrl("/users");  // "https://staging.example.com/users"

// Namespace + Enum merging
enum Color {
  Red,
  Green,
  Blue,
}

namespace Color {
  export function fromHex(hex: string): Color {
    // Custom parser
    return Color.Red;
  }
}

Color.fromHex("#ff0000");
```

### Module Augmentation (`declare module`)

Extend types from other modules without modifying their source:

```typescript
// Augmenting a third-party library
import { AxiosRequestConfig } from "axios";

declare module "axios" {
  interface AxiosRequestConfig {
    retryCount?: number;
    retryDelay?: number;
  }
}

// Now all Axios configs accept retryCount
axios.get("/api/data", { retryCount: 3, retryDelay: 1000 });

// Augmenting Vue Router
declare module "vue-router" {
  interface RouteMeta {
    requiresAuth?: boolean;
    roles?: string[];
  }
}
```

### Global Augmentation

Add types to the global scope from within a module:

```typescript
// In a .ts file that uses import/export (i.e., is a module)
export {};  // makes this a module

declare global {
  interface Window {
    __APP_CONFIG__: {
      apiUrl: string;
      environment: "dev" | "staging" | "prod";
    };
  }

  // Add a global function
  function structuredClone<T>(value: T): T;

  // Extend built-in types
  interface Array<T> {
    toSorted(compareFn?: (a: T, b: T) => number): T[];
  }
}
```

### Ambient Declarations (`.d.ts` Files)

```typescript
// types/global.d.ts — ambient declarations (no import/export)

// Declare a module that has no types
declare module "untyped-library" {
  export function doSomething(input: string): number;
  export default class Client {
    constructor(config: { apiKey: string });
    fetch(url: string): Promise<unknown>;
  }
}

// Wildcard module declarations
declare module "*.css" {
  const styles: Record<string, string>;
  export default styles;
}

declare module "*.svg" {
  const content: string;
  export default content;
}

// Declare global variables from scripts
declare const __DEV__: boolean;
declare const __VERSION__: string;
```

**Key distinction**: A `.d.ts` file without `import`/`export` is a **script** (ambient context). All declarations are global. Adding a single `import` or `export` makes it a **module**, and declarations are scoped — you must use `declare global {}` to affect the global scope.

---

## 6. Type-Level Programming

### Types as a Language

TypeScript's type system is Turing-complete. You can perform mapping, filtering, recursion, and even arithmetic at the type level.

```
Type-Level Programming Capabilities
┌──────────────────────────────────────────────┐
│  Variables     → Type parameters (generics)  │
│  Conditionals  → Conditional types            │
│  Loops         → Mapped types / recursion     │
│  Functions     → Generic type aliases          │
│  Pattern match → Template literal + infer     │
│  Data structs  → Tuples, object types         │
└──────────────────────────────────────────────┘
```

### String Manipulation Types

```typescript
// Built-in string manipulation types
type Upper = Uppercase<"hello">;       // "HELLO"
type Lower = Lowercase<"HELLO">;       // "hello"
type Cap = Capitalize<"hello">;        // "Hello"
type Uncap = Uncapitalize<"Hello">;    // "hello"

// Convert kebab-case to camelCase
type CamelCase<S extends string> =
  S extends `${infer Head}-${infer Char}${infer Tail}`
    ? `${Head}${Uppercase<Char>}${CamelCase<Tail>}`
    : S;

type Result = CamelCase<"background-color-value">;  // "backgroundColorValue"

// Convert object keys from snake_case to camelCase
type SnakeToCamel<S extends string> =
  S extends `${infer Head}_${infer Char}${infer Tail}`
    ? `${Head}${Uppercase<Char>}${SnakeToCamel<Tail>}`
    : S;

type CamelizeKeys<T> = {
  [K in keyof T as K extends string ? SnakeToCamel<K> : K]: T[K];
};

interface ApiUser {
  user_name: string;
  email_address: string;
  created_at: Date;
}

type FrontendUser = CamelizeKeys<ApiUser>;
// { userName: string; emailAddress: string; createdAt: Date }
```

### Tuple Manipulation

```typescript
// Get first element
type Head<T extends any[]> = T extends [infer H, ...any[]] ? H : never;

// Get everything except first
type Tail<T extends any[]> = T extends [any, ...infer R] ? R : never;

// Get last element
type Last<T extends any[]> = T extends [...any[], infer L] ? L : never;

// Prepend to tuple
type Prepend<T extends any[], E> = [E, ...T];

// Reverse a tuple
type Reverse<T extends any[]> =
  T extends [infer Head, ...infer Tail]
    ? [...Reverse<Tail>, Head]
    : [];

type Reversed = Reverse<[1, 2, 3, 4]>;  // [4, 3, 2, 1]

// Flatten nested tuples
type Flatten<T extends any[]> =
  T extends [infer Head, ...infer Tail]
    ? Head extends any[]
      ? [...Flatten<Head>, ...Flatten<Tail>]
      : [Head, ...Flatten<Tail>]
    : [];

type Flat = Flatten<[1, [2, 3], [4, [5]]]>;  // [1, 2, 3, 4, 5]

// Length as a number
type Length<T extends any[]> = T["length"];
type Len = Length<[string, number, boolean]>;  // 3
```

### Type-Safe Event Emitter Pattern

```typescript
type EventMap = {
  userCreated: { userId: string; name: string };
  orderPlaced: { orderId: string; total: number };
  error: { code: number; message: string };
};

class TypedEventEmitter<TEvents extends Record<string, any>> {
  private handlers = new Map<string, Set<Function>>();

  on<K extends keyof TEvents>(
    event: K,
    handler: (payload: TEvents[K]) => void
  ): () => void {
    const set = this.handlers.get(event as string) ?? new Set();
    set.add(handler);
    this.handlers.set(event as string, set);
    return () => set.delete(handler);  // unsubscribe function
  }

  emit<K extends keyof TEvents>(event: K, payload: TEvents[K]): void {
    this.handlers.get(event as string)?.forEach(fn => fn(payload));
  }
}

const emitter = new TypedEventEmitter<EventMap>();

emitter.on("userCreated", (payload) => {
  console.log(payload.userId);  // Fully typed!
});

emitter.emit("orderPlaced", { orderId: "123", total: 99.99 });  // OK
emitter.emit("orderPlaced", { orderId: "123" });                 // Error: missing total
emitter.emit("unknownEvent", {});                                 // Error: not in EventMap
```

### Builder Pattern with Accumulating Types

```typescript
type QueryBuilder<
  TSelect extends Record<string, any> = {},
  THaving extends boolean = false
> = {
  select<K extends string, V>(
    key: K,
    value: V
  ): QueryBuilder<TSelect & Record<K, V>, THaving>;

  where(condition: string): QueryBuilder<TSelect, THaving>;

  groupBy(field: keyof TSelect): QueryBuilder<TSelect, true>;

  having(
    ...args: THaving extends true ? [string] : [never]
  ): QueryBuilder<TSelect, THaving>;

  execute(): TSelect[];
};

// Usage tracks accumulated type through the chain
declare function createQuery(): QueryBuilder;

const result = createQuery()
  .select("name", "" as string)
  .select("age", 0 as number)
  .where("age > 18")
  .execute();
// result is { name: string } & { age: number }[]

// .having() only available after .groupBy()
```

### Parsing Route Parameters from String Types

```typescript
// Extract typed params from route strings like "/users/:id/posts/:postId"
type ExtractParams<T extends string> =
  T extends `${string}:${infer Param}/${infer Rest}`
    ? { [K in Param | keyof ExtractParams<Rest>]: string }
    : T extends `${string}:${infer Param}`
      ? { [K in Param]: string }
      : {};

type UserPostParams = ExtractParams<"/users/:userId/posts/:postId">;
// { userId: string; postId: string }

// Type-safe router
function defineRoute<T extends string>(
  path: T,
  handler: (params: ExtractParams<T>) => void
): void {
  /* ... */
}

defineRoute("/users/:userId/posts/:postId", (params) => {
  console.log(params.userId);   // OK — string
  console.log(params.postId);   // OK — string
  console.log(params.unknown);  // Error: no such property
});
```

---

## 7. TypeScript Compiler Internals & Configuration

### `strict` Mode and Its Sub-Flags

`"strict": true` enables ALL strict checks. Understanding each sub-flag is essential:

```jsonc
{
  "compilerOptions": {
    "strict": true
    // Equivalent to enabling ALL of the following:
    // "strictNullChecks": true          — null/undefined are distinct types
    // "strictFunctionTypes": true       — contravariant function parameter checking
    // "strictBindCallApply": true       — typed bind/call/apply
    // "strictPropertyInitialization": true — class props must be initialized
    // "noImplicitAny": true             — error on implicit any
    // "noImplicitThis": true            — error on implicit this
    // "alwaysStrict": true              — emit "use strict"
    // "useUnknownInCatchVariables": true — catch variable is unknown (not any)
    // "exactOptionalPropertyTypes": true — (TS 4.4+) undefined not assignable
    //                                     to optional properties
  }
}
```

**Critical: `strictFunctionTypes`**

Without this flag, function parameters are checked **bivariantly** (unsound). With it, they are checked **contravariantly** (sound):

```typescript
type Handler = (event: MouseEvent) => void;

const handler: Handler = (event: Event) => {};
// With strictFunctionTypes: Error! Event is not assignable to MouseEvent
// Without: OK (unsound — the handler might access MouseEvent-specific props)
```

**Exception**: Methods are still bivariant even with `strictFunctionTypes`. Only function-type properties and callbacks are contravariant.

```typescript
interface A {
  method(x: string): void;        // Method — bivariant (less strict)
  callback: (x: string) => void;  // Property — contravariant (strict)
}
```

### `moduleResolution` Strategies

```
Module Resolution Strategies
┌────────────────────────────────────────────────────────────────┐
│                                                                │
│  "node" (Classic Node.js / node10)                             │
│    └── Mimics Node.js require()                                │
│    └── Does NOT support package.json "exports"                 │
│    └── Legacy — avoid for new projects                         │
│                                                                │
│  "node16" / "nodenext"                                         │
│    └── Full ESM support with .mjs/.cjs distinction             │
│    └── Supports package.json "exports" field                   │
│    └── Requires file extensions in relative imports            │
│    └── import "./foo.js" (even if source is .ts)               │
│    └── Use for Node.js libraries and apps                      │
│                                                                │
│  "bundler" (TS 5.0+)                                           │
│    └── Assumes a bundler handles resolution                    │
│    └── Supports "exports" but does NOT require extensions      │
│    └── import "./foo" works (no .js needed)                    │
│    └── Use with Vite, webpack, esbuild, etc.                   │
│                                                                │
└────────────────────────────────────────────────────────────────┘
```

**Interview question**: "Why does TypeScript require `.js` extensions in imports when using `node16`?"

Because TypeScript does not rewrite import specifiers during compilation. The emitted JS must use the extension that Node.js will actually resolve. Since `.ts` files compile to `.js`, you write `import "./foo.js"` even though `foo.ts` is the source.

### `isolatedModules` and Why It Matters

When `isolatedModules` is true, TypeScript ensures every file can be transpiled independently (by Babel, esbuild, swc, etc.) without needing type information from other files.

What it prohibits:

```typescript
// 1. Re-exporting types without `type` keyword
export { User } from "./types";         // Error if User is a type
export { type User } from "./types";    // OK — explicit type export

// 2. const enums (require cross-file knowledge to inline)
const enum Direction { Up, Down }       // Error
enum Direction { Up, Down }             // OK

// 3. Non-module files (files without import/export)
// Must have at least one import or export

// 4. Namespace merging across files
```

**Why it matters**: Most modern build tools (esbuild, swc, Vite) transpile files independently for speed. They cannot perform cross-file type analysis. `isolatedModules` ensures your code is compatible.

### Project References and Composite Builds

For large monorepos with multiple packages:

```jsonc
// packages/shared/tsconfig.json
{
  "compilerOptions": {
    "composite": true,       // Required for project references
    "declaration": true,     // Must emit .d.ts files
    "declarationMap": true,  // Source maps for .d.ts (Go to Definition)
    "outDir": "./dist"
  }
}

// packages/app/tsconfig.json
{
  "compilerOptions": { /* ... */ },
  "references": [
    { "path": "../shared" }
  ]
}
```

Build with `tsc --build` (or `tsc -b`). Benefits:
- Incremental compilation across projects
- Dependency graph ensures correct build order
- Each project type-checks independently

### `satisfies` vs `as` vs `as const`

```typescript
const config = {
  port: 3000,
  host: "localhost",
  debug: true,
} as const;
// Type: { readonly port: 3000; readonly host: "localhost"; readonly debug: true }
// as const: maximally narrow + readonly. No validation against a type.

type Config = { port: number; host: string; debug: boolean };

const config2 = {
  port: 3000,
  host: "localhost",
  debug: true,
} satisfies Config;
// Type: { port: number; host: string; debug: boolean }
// satisfies: validates shape, preserves inferred types (not widened to Config).

const config3 = {
  port: 3000,
  host: "localhost",
  debug: true,
} as Config;
// Type: Config (exactly). No validation — you're asserting.
// DANGER: as does not check the shape at all for compatible types.

// Combining as const + satisfies (TS 5.0+ idiom)
const routes = {
  home: "/",
  about: "/about",
  user: "/user/:id",
} as const satisfies Record<string, string>;
// Validates shape AND preserves literal types + readonly
```

### `const` Type Parameters (TS 5.0)

```typescript
// Without const — T inferred broadly
function createPair<T>(a: T, b: T) { return [a, b] as const; }
const pair = createPair("hello", "world");
// T = string, result: readonly [string, string]

// With const — T inferred as literal
function createPairConst<const T>(a: T, b: T) { return [a, b] as const; }
const pairConst = createPairConst("hello", "world");
// Error! "hello" and "world" are different literal types

// More useful example
function defineConfig<const T extends Record<string, unknown>>(config: T): T {
  return config;
}

const cfg = defineConfig({
  routes: ["/api", "/health"] as const,
  port: 3000,
});
// cfg.port is 3000 (literal), cfg.routes is readonly ["/api", "/health"]
```

### Performance: Avoiding Deep Instantiation

```typescript
// BAD — deeply recursive type can crash the compiler
type DeepExpand<T> = T extends object
  ? { [K in keyof T]: DeepExpand<T[K]> }
  : T;

// The compiler has a recursion depth limit (~50 levels by default)
// and an instantiation count limit (default ~5,000,000)

// GOOD — limit recursion depth with a counter
type DeepPartialBounded<T, Depth extends number[] = []> =
  Depth["length"] extends 10  // Stop at depth 10
    ? T
    : T extends object
      ? { [K in keyof T]?: DeepPartialBounded<T[K], [...Depth, 0]> }
      : T;

// Performance tips:
// - Avoid distributing over large unions in mapped types
// - Use interfaces over type aliases when possible (interfaces are cached)
// - Avoid deeply nested conditional types
// - Use `@ts-expect-error` to skip known-problematic lines rather than
//   adding complexity to make the type system happy
```

### `isolatedDeclarations` (TS 5.5+)

TS 5.5+ supports `--isolatedDeclarations` for parallel declaration-only emits in monorepos; requires explicit return types on exports. This lets external tools (e.g., bundlers, build accelerators) generate `.d.ts` files in parallel without running the full type checker, dramatically speeding up builds in large workspaces.

### Stage-3 vs Legacy Decorators

TS 5.0+ ships stage-3 decorators by default; `experimentalDecorators: true` is still needed for legacy decorators (required by older versions of NestJS/TypeORM). Runtime semantics differ — legacy decorators use `Reflect.metadata`, stage-3 do not.

### TypeScript Version Baseline

As of 2026, TypeScript 5.7+ is current stable.

---

## 8. Common Pitfalls & Anti-patterns

### `any` vs `unknown` vs `never`

```
Type Hierarchy (simplified)
┌──────────────────────────────────────────────────┐
│                   unknown                         │
│        (top type — every type extends it)         │
│  ┌─────────────────────────────────────────────┐  │
│  │    string  number  boolean  object  ...     │  │
│  │    (all concrete types live here)           │  │
│  └─────────────────────────────────────────────┘  │
│                    never                          │
│       (bottom type — extends every type)          │
└──────────────────────────────────────────────────┘

        any — OPTS OUT of the type system entirely
        (assignable TO and FROM everything — breaks soundness)
```

```typescript
// unknown — safe "I don't know what this is"
function parseJSON(json: string): unknown {
  return JSON.parse(json);
}

const data: unknown = parseJSON('{"name": "Alice"}');
// data.name;       // Error! Must narrow first
if (typeof data === "object" && data !== null && "name" in data) {
  console.log(data.name);  // OK after narrowing
}

// any — unsafe escape hatch (disables ALL checking)
const data2: any = parseJSON('{"name": "Alice"}');
data2.name;          // No error — but no safety either
data2.foo.bar.baz;   // No error — crashes at runtime

// never — represents impossibility
function throwError(message: string): never {
  throw new Error(message);  // Function never returns
}

// never is the empty union — no value can satisfy it
type Empty = string & number;  // never
type Never = Extract<string, number>;  // never

// Practical: never in conditional types for filtering
type OnlyStrings<T> = T extends string ? T : never;
type Result = OnlyStrings<string | number | boolean>;  // string
```

**Rule of thumb**: Use `unknown` for values you receive from external boundaries (API responses, user input, JSON parsing). Use `any` only as a last resort when interfacing with untyped JS code. Use `never` for exhaustive checks and impossible states.

### Overusing Enums

```typescript
// PROBLEMATIC — TypeScript enums have many quirks
enum Status {
  Active = "ACTIVE",
  Inactive = "INACTIVE",
}

// 1. Numeric enums allow any number (unsafe)
enum Direction { Up, Down, Left, Right }
const d: Direction = 42;  // No error! Enums accept any number

// 2. const enums break with isolatedModules
const enum Color { Red, Green, Blue }  // Can't use with esbuild/swc

// 3. Enums are nominal — can't assign a matching string
const status: Status = "ACTIVE";  // Error even though values match

// RECOMMENDED — union types or const objects
// Option A: String union (simplest)
type Status = "ACTIVE" | "INACTIVE";
const status: Status = "ACTIVE";  // Works naturally

// Option B: Const object (when you need runtime values + type)
const Status = {
  Active: "ACTIVE",
  Inactive: "INACTIVE",
} as const;
type Status = (typeof Status)[keyof typeof Status];
// Status = "ACTIVE" | "INACTIVE"

// You get both: the runtime object for iteration/lookup
// AND the union type for type checking
Object.values(Status).forEach(s => console.log(s));
```

### Type Assertions Masking Real Bugs

```typescript
// DANGEROUS — assertion hides a real type error
interface User {
  id: string;
  name: string;
  email: string;
}

const user = {} as User;       // No error — but user is empty at runtime!
console.log(user.name.length); // Runtime crash: Cannot read property 'length' of undefined

// SAFER alternatives:
// 1. Construct the object properly
const user2: User = { id: "1", name: "Alice", email: "a@b.com" };

// 2. Use a factory function
function createUser(data: User): User { return data; }

// 3. Use satisfies for validation
const user3 = {
  id: "1",
  name: "Alice",
  email: "a@b.com",
} satisfies User;

// ACCEPTABLE uses of assertions:
// - After a runtime check that TypeScript can't follow
const el = document.getElementById("root");
if (!el) throw new Error("Missing root");
const root = el as HTMLDivElement;  // OK — we checked existence

// - In tests where you're deliberately testing edge cases
// - Working around a third-party library's incorrect types
```

### Index Signatures and Their Unsafety

```typescript
interface StringMap {
  [key: string]: string;
}

const map: StringMap = { name: "Alice" };
const value: string = map["nonexistent"];
// TypeScript says value is string — but it's actually undefined!
// This is UNSOUND by default

// FIX 1: Enable noUncheckedIndexedAccess (tsconfig)
// With this flag, map["key"] returns string | undefined
// Requires you to check before using

// FIX 2: Use Map<string, string> instead
const safeMap = new Map<string, string>();
safeMap.set("name", "Alice");
const val = safeMap.get("nonexistent");  // string | undefined (correct!)

// FIX 3: Use Record with known keys
type KnownMap = Record<"name" | "email", string>;
const known: KnownMap = { name: "Alice", email: "a@b.com" };
known["name"];  // string (safe — keys are known)
```

**`noUncheckedIndexedAccess`** is NOT included in `strict` mode. You must enable it separately. It is strongly recommended for production codebases.

### Excess Property Checking Quirks

```typescript
interface Config {
  host: string;
  port: number;
}

// Direct literal — excess properties caught
const config: Config = {
  host: "localhost",
  port: 3000,
  timeout: 5000,  // Error: excess property
};

// Via variable — excess properties SILENTLY allowed
const raw = { host: "localhost", port: 3000, timeout: 5000 };
const config2: Config = raw;  // No error!

// Via function argument — caught for direct literals only
function startServer(config: Config) { /* ... */ }
startServer({ host: "localhost", port: 3000, timeout: 5000 });  // Error
startServer(raw);  // No error!

// Via spread — excess properties silently included
const config3: Config = { ...raw };  // No error — but timeout is there at runtime

// WORKAROUND: Use Exact type helper (not built-in)
type Exact<T, Shape> = T extends Shape
  ? Exclude<keyof T, keyof Shape> extends never
    ? T
    : never
  : never;

function startServerExact<T extends Config>(
  config: Exact<T, Config>
): void { /* ... */ }
```

### `object` vs `Object` vs `{}`

```typescript
// {} — any non-null, non-undefined value (extremely wide)
let a: {} = "hello";    // OK
let b: {} = 42;         // OK
let c: {} = true;       // OK
let d: {} = {};         // OK
let e: {} = null;       // Error (with strictNullChecks)
// {} means "has no known properties" — but could be anything non-nullish

// object — any non-primitive value (objects, arrays, functions)
let f: object = {};          // OK
let g: object = [];          // OK
let h: object = () => {};    // OK
let i: object = "hello";    // Error: string is a primitive
let j: object = 42;         // Error: number is a primitive

// Object — the Object interface (DO NOT USE as a type)
// Matches any value with Object.prototype methods (toString, etc.)
// Almost the same as {} — confusing and should be avoided
let k: Object = "hello";    // OK (string has .toString())
let l: Object = null;       // Error
```

**Summary table**:

| Type | Primitives | Objects | null/undefined |
|------|-----------|---------|----------------|
| `{}` | Yes | Yes | No (strict) |
| `object` | No | Yes | No |
| `Object` | Yes | Yes | No (strict) |
| `unknown` | Yes | Yes | Yes |
| `any` | Yes | Yes | Yes |

**Rule**: Use `object` when you mean "not a primitive". Use `Record<string, unknown>` when you mean "an object with string keys". Never use `Object` or `{}` as a type annotation — they're almost always too wide.

### Bonus: Function Overloads vs Union Parameters

```typescript
// OVERLOADS — specific input → specific output mapping
function parse(input: string): Document;
function parse(input: Buffer): Document;
function parse(input: string | Buffer): Document {
  // Implementation signature is not callable directly
  if (typeof input === "string") {
    return parseString(input);
  }
  return parseBuffer(input);
}

// WHEN TO USE OVERLOADS:
// - Different return types based on input type
// - Complex relationship between parameters

// PREFER UNION PARAMETERS when return type is the same:
// BAD — unnecessary overloads
function format(input: string): string;
function format(input: number): string;
function format(input: string | number): string {
  return String(input);
}

// GOOD — simpler
function format(input: string | number): string {
  return String(input);
}
```

### Bonus: The `this` Type in Classes

```typescript
// `this` as a return type enables fluent chaining with inheritance
class QueryBuilder {
  private conditions: string[] = [];

  where(condition: string): this {
    this.conditions.push(condition);
    return this;
  }

  limit(n: number): this {
    // ...
    return this;
  }
}

class AdvancedQueryBuilder extends QueryBuilder {
  join(table: string): this {
    // ...
    return this;
  }
}

// Without `this` return type, this chain would fail:
new AdvancedQueryBuilder()
  .where("age > 18")    // returns AdvancedQueryBuilder (not QueryBuilder)
  .join("orders")       // still has .join() because `this` tracks the subclass
  .limit(10);
```

---

## Quick Reference: Interview Questions Cheat Sheet

**Q: Is TypeScript's type system sound?**
No. TypeScript is intentionally unsound in several places: `any`, bivariant method parameters, array covariance, enum number assignment, and type assertions. The design philosophy prioritizes practical usability over theoretical soundness.

**Q: What is the difference between `type` and `interface`?**
Interfaces support declaration merging (can be extended by re-declaring). Types support unions, intersections, mapped types, and conditional types. Interfaces are slightly more performant (cached by name). Use interfaces for public API surfaces and types for unions/utilities.

**Q: What is a discriminated union?**
A union of object types where each variant has a shared property (the "discriminant") with a unique literal type. TypeScript narrows based on checking this property. Essential for modeling state machines, ASTs, and domain events.

**Q: Explain `infer` in one sentence.**
`infer` declares a type variable inside a conditional type's `extends` clause, allowing you to extract and capture part of a type for use in the true branch.

**Q: When should you use `unknown` over `any`?**
Always prefer `unknown` when the type is genuinely not known. `unknown` forces you to narrow before using the value, catching bugs at compile time. `any` disables all checking and is a viral escape hatch (anything touching `any` becomes `any`).

**Q: What does `as const` do?**
It requests the narrowest possible type inference: string literals instead of `string`, readonly tuples instead of mutable arrays, and readonly properties. It does NOT validate against any type — just narrows inference.

**Q: How do you make TypeScript error when a switch is not exhaustive?**
Assign the value in the `default` case to a variable of type `never`. If any case is unhandled, the value won't be `never` and the compiler will error.
