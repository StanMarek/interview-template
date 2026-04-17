# Databases & ORMs -- Senior Engineer Interview Preparation

---

## 1. ORM Landscape

### Why ORMs in Node.js

Node.js lacks a dominant persistence framework like JPA/Hibernate in Java. The ecosystem offers multiple ORMs with fundamentally different philosophies. Choosing the right one affects type safety, query flexibility, migration strategy, and team velocity.

### Prisma: Schema-First, Type-Safe

Prisma uses its own schema language (`.prisma` files) as the single source of truth. It generates a fully typed client from the schema, providing autocomplete and compile-time safety.

```prisma
// prisma/schema.prisma
generator client {
  provider = "prisma-client-js"
}

datasource db {
  provider = "postgresql"
  url      = env("DATABASE_URL")
}

model User {
  id        Int       @id @default(autoincrement())
  email     String    @unique
  name      String?
  posts     Post[]
  profile   Profile?
  createdAt DateTime  @default(now())
  updatedAt DateTime  @updatedAt

  @@index([email])
  @@map("users")
}

model Post {
  id        Int      @id @default(autoincrement())
  title     String
  content   String?
  published Boolean  @default(false)
  author    User     @relation(fields: [authorId], references: [id])
  authorId  Int
  tags      Tag[]

  @@index([authorId])
  @@map("posts")
}

model Profile {
  id     Int    @id @default(autoincrement())
  bio    String
  user   User   @relation(fields: [userId], references: [id])
  userId Int    @unique
}

model Tag {
  id    Int    @id @default(autoincrement())
  name  String @unique
  posts Post[]
}
```

```typescript
// Usage: fully typed, autocomplete on every field and relation
import { PrismaClient } from '@prisma/client';

const prisma = new PrismaClient();

// Type-safe query -- TypeScript knows the return type includes posts
const user = await prisma.user.findUnique({
  where: { email: 'alice@example.com' },
  include: { posts: { where: { published: true } } },
});
// user.posts is Post[] -- fully typed
```

### TypeORM: Decorator-Based, Active Record & Data Mapper

TypeORM follows conventions closer to Hibernate/JPA. It supports both Active Record (entity methods) and Data Mapper (repository pattern) approaches.

```typescript
// Entity definition with decorators
import {
  Entity, PrimaryGeneratedColumn, Column, OneToMany,
  CreateDateColumn, UpdateDateColumn, Index,
} from 'typeorm';

@Entity('users')
export class User {
  @PrimaryGeneratedColumn()
  id: number;

  @Index({ unique: true })
  @Column({ type: 'varchar', length: 255 })
  email: string;

  @Column({ type: 'varchar', nullable: true })
  name: string | null;

  @OneToMany(() => Post, (post) => post.author)
  posts: Post[];

  @CreateDateColumn()
  createdAt: Date;

  @UpdateDateColumn()
  updatedAt: Date;
}

// Active Record pattern -- methods on the entity itself
const user = await User.findOneBy({ email: 'alice@example.com' });

// Data Mapper pattern -- repository holds query logic
const userRepo = dataSource.getRepository(User);
const user = await userRepo.findOne({
  where: { email: 'alice@example.com' },
  relations: { posts: true },
});
```

### Drizzle: SQL-Like, Lightweight, Type-Safe

Drizzle embraces SQL syntax directly in TypeScript. No schema file, no code generation step -- the TypeScript table definitions ARE the schema.

```typescript
// Schema definition -- pure TypeScript
import { pgTable, serial, varchar, text, boolean, integer, timestamp } from 'drizzle-orm/pg-core';
import { relations } from 'drizzle-orm';

export const users = pgTable('users', {
  id: serial('id').primaryKey(),
  email: varchar('email', { length: 255 }).notNull().unique(),
  name: varchar('name', { length: 255 }),
  createdAt: timestamp('created_at').defaultNow().notNull(),
});

export const posts = pgTable('posts', {
  id: serial('id').primaryKey(),
  title: varchar('title', { length: 255 }).notNull(),
  content: text('content'),
  published: boolean('published').default(false).notNull(),
  authorId: integer('author_id').notNull().references(() => users.id),
});

export const usersRelations = relations(users, ({ many }) => ({
  posts: many(posts),
}));

export const postsRelations = relations(posts, ({ one }) => ({
  author: one(users, { fields: [posts.authorId], references: [users.id] }),
}));

// Queries -- SQL-like syntax, fully typed
import { eq, and, gt } from 'drizzle-orm';
import { drizzle } from 'drizzle-orm/node-postgres';

const db = drizzle(pool, { schema });

// Select with joins
const result = await db
  .select({
    userName: users.name,
    postTitle: posts.title,
  })
  .from(users)
  .innerJoin(posts, eq(users.id, posts.authorId))
  .where(and(eq(posts.published, true), gt(posts.id, 10)));

// Relational query API (similar to Prisma includes)
const usersWithPosts = await db.query.users.findMany({
  with: { posts: true },
});
```

### ORM Comparison Table

| Feature | Prisma | TypeORM | Drizzle |
|---------|--------|---------|---------|
| **Approach** | Schema-first (`.prisma`) | Decorator-based entities | SQL-like TypeScript |
| **Type Safety** | Generated client, excellent | Decorators, good but gaps | Inferred from schema, excellent |
| **Code Generation** | Required (`prisma generate`) | None | None |
| **Migration** | Built-in (`prisma migrate`) | Built-in (sync or generate) | `drizzle-kit` (separate pkg) |
| **Raw SQL** | `$queryRaw` / `$executeRaw` | `query()` on DataSource | `sql` template literal |
| **Query Builder** | Fluent object API | QueryBuilder + find API | SQL-like builder |
| **Relation Loading** | `include` / `select` nested | `relations` / QueryBuilder | `with` in relational queries |
| **Connection Pooling** | Internal pool + external support | Via underlying driver | Via underlying driver |
| **Bundle Size** | Larger (engine binary) | Medium | Smallest |
| **Performance** | Good (Rust engine) | Moderate | Best (thin SQL wrapper) |
| **Learning Curve** | Low (own DSL) | Medium (decorator patterns) | Low if you know SQL |
| **Best For** | Rapid development, startups | Enterprise, complex domain models | Performance-critical, SQL-literate teams |

### When to Use Each

- **Prisma**: Greenfield projects, teams wanting maximum type safety with minimal SQL knowledge. Excellent DX and documentation. Trade-off: schema language lock-in, engine binary overhead.
- **TypeORM**: Teams coming from Java/C# who want familiar patterns. Complex domain models with inheritance. Trade-off: known bugs in complex queries, slower maintenance velocity.
- **Drizzle**: Teams that want to stay close to SQL. Performance-sensitive applications. Existing databases where you need fine-grained query control. Trade-off: less abstraction means more SQL knowledge required.

---

## 2. Prisma Deep Dive

### Schema Language and Code Generation

```
┌─────────────────┐     prisma generate     ┌──────────────────────┐
│  schema.prisma  │ ──────────────────────→  │  @prisma/client      │
│  (source of     │                          │  (generated types +  │
│   truth)        │     prisma migrate       │   query engine)      │
└─────────────────┘ ──────────────────────→  ┌──────────────────────┐
                                             │  SQL migrations      │
                                             │  (versioned in repo) │
                                             └──────────────────────┘
```

`prisma generate` reads the schema and produces a fully typed TypeScript client in `node_modules/.prisma/client`. This must be re-run after every schema change. CI/CD pipelines must include this step after `npm install`.

### Client API

```typescript
const prisma = new PrismaClient({
  log: ['query', 'warn', 'error'],  // Enable query logging
});

// --- findMany: list with filtering, ordering, pagination ---
const recentPosts = await prisma.post.findMany({
  where: {
    published: true,
    author: { email: { endsWith: '@company.com' } },  // Nested filter
    createdAt: { gte: new Date('2025-01-01') },
  },
  orderBy: { createdAt: 'desc' },
  take: 20,       // LIMIT
  skip: 0,        // OFFSET
  include: {
    author: { select: { name: true, email: true } },
    tags: true,
  },
});

// --- findUnique: single record by unique field ---
const user = await prisma.user.findUnique({
  where: { email: 'alice@example.com' },
});
// Returns null if not found (no exception)

// --- findUniqueOrThrow: throws PrismaClientKnownRequestError if not found ---
const user = await prisma.user.findUniqueOrThrow({
  where: { id: 1 },
});

// --- create: insert with nested relations ---
const newUser = await prisma.user.create({
  data: {
    email: 'bob@example.com',
    name: 'Bob',
    posts: {
      create: [
        { title: 'First Post', content: 'Hello world', published: true },
        { title: 'Draft', content: 'WIP' },
      ],
    },
    profile: {
      create: { bio: 'Software engineer' },
    },
  },
  include: { posts: true, profile: true },
});

// --- update: modify existing record ---
const updated = await prisma.post.update({
  where: { id: 1 },
  data: {
    published: true,
    tags: {
      connect: [{ id: 1 }, { id: 2 }],   // Link existing tags
      create: [{ name: 'typescript' }],     // Create and link new tag
    },
  },
});

// --- upsert: create if not exists, update if exists ---
const user = await prisma.user.upsert({
  where: { email: 'alice@example.com' },
  update: { name: 'Alice Updated' },
  create: { email: 'alice@example.com', name: 'Alice' },
});

// --- delete with cascade considerations ---
// Prisma does NOT auto-cascade by default -- configure in schema:
// onDelete: Cascade, SetNull, Restrict, NoAction
await prisma.user.delete({ where: { id: 1 } });

// --- createMany: bulk insert (no nested creates) ---
await prisma.post.createMany({
  data: posts.map((p) => ({ title: p.title, authorId: p.authorId })),
  skipDuplicates: true,  // Ignore rows that violate unique constraints
});

// --- aggregate: count, sum, avg, min, max ---
const stats = await prisma.post.aggregate({
  _count: true,
  _avg: { viewCount: true },
  where: { published: true },
});

// --- groupBy ---
const postsByAuthor = await prisma.post.groupBy({
  by: ['authorId'],
  _count: { id: true },
  _sum: { viewCount: true },
  having: { viewCount: { _sum: { gt: 100 } } },
  orderBy: { _sum: { viewCount: 'desc' } },
});
```

### Relations in Detail

```prisma
// One-to-One: User <-> Profile
model User {
  id      Int      @id @default(autoincrement())
  profile Profile?
}

model Profile {
  id     Int  @id @default(autoincrement())
  user   User @relation(fields: [userId], references: [id], onDelete: Cascade)
  userId Int  @unique   // @unique makes it 1:1 (not 1:many)
}

// One-to-Many: User -> Post[]
model User {
  id    Int    @id @default(autoincrement())
  posts Post[]
}

model Post {
  id       Int  @id @default(autoincrement())
  author   User @relation(fields: [authorId], references: [id])
  authorId Int
}

// Many-to-Many: implicit join table (Prisma manages it)
model Post {
  id   Int   @id @default(autoincrement())
  tags Tag[]
}

model Tag {
  id    Int    @id @default(autoincrement())
  posts Post[]
}
// Creates _PostToTag join table automatically

// Many-to-Many: explicit join table (when you need extra fields)
model PostTag {
  post      Post     @relation(fields: [postId], references: [id])
  postId    Int
  tag       Tag      @relation(fields: [tagId], references: [id])
  tagId     Int
  assignedAt DateTime @default(now())

  @@id([postId, tagId])
}

// Self-relation: Employee -> manager/reports
model Employee {
  id        Int        @id @default(autoincrement())
  name      String
  managerId Int?
  manager   Employee?  @relation("Management", fields: [managerId], references: [id])
  reports   Employee[] @relation("Management")
}
```

### Transactions

```typescript
// Sequential transaction -- queries run one after another, share a connection
const [updatedUser, newPost] = await prisma.$transaction([
  prisma.user.update({ where: { id: 1 }, data: { name: 'Updated' } }),
  prisma.post.create({ data: { title: 'New', authorId: 1 } }),
]);

// Interactive transaction -- full control, can use results of prior queries
const transfer = await prisma.$transaction(async (tx) => {
  // tx is a Prisma client scoped to this transaction
  const sender = await tx.account.update({
    where: { id: senderId },
    data: { balance: { decrement: amount } },
  });

  if (sender.balance < 0) {
    throw new Error('Insufficient funds');  // Rolls back entire transaction
  }

  const receiver = await tx.account.update({
    where: { id: receiverId },
    data: { balance: { increment: amount } },
  });

  return { sender, receiver };
}, {
  maxWait: 5000,       // Max time to acquire a connection (ms)
  timeout: 10000,      // Max transaction duration (ms)
  isolationLevel: 'Serializable',  // Optional: ReadUncommitted, ReadCommitted,
                                   //           RepeatableRead, Serializable
});
```

**Gotcha**: Sequential transactions (`$transaction([...])`) do NOT allow using results from one operation in another. Use interactive transactions when queries depend on each other.

### Raw Queries

```typescript
// Tagged template -- parameterized (SQL injection safe)
const users = await prisma.$queryRaw<User[]>`
  SELECT u.id, u.name, COUNT(p.id) as post_count
  FROM users u
  LEFT JOIN posts p ON u.id = p.author_id
  WHERE u.created_at > ${startDate}
  GROUP BY u.id, u.name
  HAVING COUNT(p.id) > ${minPosts}
  ORDER BY post_count DESC
`;

// Execute (for INSERT, UPDATE, DELETE -- returns affected row count)
const affected = await prisma.$executeRaw`
  UPDATE posts SET view_count = view_count + 1 WHERE id = ${postId}
`;

// DANGER: Prisma.sql for dynamic table/column names (rare, be careful)
import { Prisma } from '@prisma/client';
const table = Prisma.raw('users');  // NOT parameterized -- validate input
```

### Middleware (Query Hooks)

```typescript
// Middleware intercepts every query -- use for logging, soft deletes, audit
prisma.$use(async (params, next) => {
  const startTime = Date.now();

  // Soft delete: intercept delete and convert to update
  if (params.action === 'delete') {
    params.action = 'update';
    params.args.data = { deletedAt: new Date() };
  }

  if (params.action === 'findMany' || params.action === 'findFirst') {
    // Auto-filter soft-deleted records
    params.args.where = { ...params.args.where, deletedAt: null };
  }

  const result = await next(params);

  const duration = Date.now() - startTime;
  console.log(`${params.model}.${params.action} took ${duration}ms`);

  return result;
});

// NOTE: Middleware was **Removed** in Prisma v6.14.0 (Aug 2025). Use Prisma Client Extensions instead. The examples below are historical — they no longer run on current Prisma.
// Client extensions are more type-safe and composable:
const xprisma = prisma.$extends({
  query: {
    user: {
      async findMany({ model, operation, args, query }) {
        args.where = { ...args.where, deletedAt: null };
        return query(args);
      },
    },
  },
});
```

### Connection Pooling

```
┌──────────────────────────────────────────────────────────┐
│                     Node.js Application                  │
│   ┌──────────────┐                                       │
│   │ PrismaClient │──┐                                    │
│   └──────────────┘  │  connection_limit=10               │
│   ┌──────────────┐  │                                    │
│   │ PrismaClient │──┼──→  Prisma Query Engine            │
│   └──────────────┘  │     (Rust binary, internal pool)   │
│   ┌──────────────┐  │                                    │
│   │ PrismaClient │──┘                                    │
│   └──────────────┘                                       │
└──────────────────────────────────────────────────────────┘
                          │
              ┌───────────┴───────────┐
              │  PgBouncer (optional) │  ← External pooler for
              │  or Prisma Accelerate │    serverless / high concurrency
              └───────────┬───────────┘
                          │
              ┌───────────┴───────────┐
              │    PostgreSQL          │
              │    max_connections=100 │
              └───────────────────────┘
```

```typescript
// Connection pool configuration via DATABASE_URL
// postgresql://user:pass@host:5432/db?connection_limit=10&pool_timeout=10

// IMPORTANT: In serverless (Lambda, Vercel Functions), each invocation
// may create a new PrismaClient. Use a singleton pattern:
import { PrismaClient } from '@prisma/client';

const globalForPrisma = globalThis as unknown as { prisma: PrismaClient };

export const prisma = globalForPrisma.prisma ?? new PrismaClient();

if (process.env.NODE_ENV !== 'production') {
  globalForPrisma.prisma = prisma;
}

// For serverless at scale, use Prisma Accelerate (managed connection pool)
// or PgBouncer in transaction mode
```

### Migration Workflow

```bash
# Development: generate migration from schema diff
npx prisma migrate dev --name add_user_email_index
# 1. Diffs schema.prisma against current migration history
# 2. Generates SQL migration file
# 3. Applies migration to dev database
# 4. Runs prisma generate

# Production: apply pending migrations (no generation)
npx prisma migrate deploy
# Only runs unapplied migrations -- safe for CI/CD

# Reset: drop database, re-apply all migrations (DEV ONLY)
npx prisma migrate reset

# Inspect: view migration status
npx prisma migrate status

# Escape hatch: manually edit generated SQL before applying
# Edit the .sql file in prisma/migrations/<timestamp>_<name>/
```

**Migration file structure**:
```
prisma/
  migrations/
    20250101_init/
      migration.sql
    20250115_add_user_email_index/
      migration.sql
    migration_lock.toml    # Prevents concurrent migrations
  schema.prisma
```

### Common Pitfalls

**N+1 Queries**:
```typescript
// BAD: N+1 -- one query per user to get posts
const users = await prisma.user.findMany();
for (const user of users) {
  const posts = await prisma.post.findMany({ where: { authorId: user.id } });
  // This fires N additional queries
}

// GOOD: Use include to eager-load in a single query
const users = await prisma.user.findMany({
  include: { posts: true },
});
// Prisma generates: SELECT users; SELECT posts WHERE authorId IN (1,2,3,...)
```

**Large Result Sets**: `findMany()` without `take` loads ALL rows into memory. Always paginate.

**Missing Indexes**: Prisma schema `@@index` is just metadata -- it generates CREATE INDEX in migrations. Forgetting `@@index` on frequently queried foreign keys causes sequential scans.

---

## 3. MongoDB with Mongoose

### Schema Definition and Validation

```typescript
import mongoose, { Schema, Document, Model, Types } from 'mongoose';

// TypeScript interface for the document
interface IUser extends Document {
  email: string;
  name: string;
  age?: number;
  role: 'user' | 'admin' | 'moderator';
  address: {
    street: string;
    city: string;
    country: string;
  };
  tags: string[];
  posts: Types.ObjectId[];
  createdAt: Date;
  updatedAt: Date;
  fullName: string;          // Virtual
  isAdult: () => boolean;    // Method
}

// Static methods interface
interface IUserModel extends Model<IUser> {
  findByEmail(email: string): Promise<IUser | null>;
}

const userSchema = new Schema<IUser, IUserModel>(
  {
    email: {
      type: String,
      required: [true, 'Email is required'],
      unique: true,
      lowercase: true,
      trim: true,
      validate: {
        validator: (v: string) => /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(v),
        message: 'Invalid email format',
      },
    },
    name: {
      type: String,
      required: true,
      minlength: [2, 'Name must be at least 2 characters'],
      maxlength: 100,
    },
    age: {
      type: Number,
      min: [0, 'Age cannot be negative'],
      max: 150,
    },
    role: {
      type: String,
      enum: ['user', 'admin', 'moderator'],
      default: 'user',
    },
    address: {
      street: { type: String, required: true },
      city: { type: String, required: true },
      country: { type: String, required: true },
    },
    tags: [{ type: String, index: true }],
    posts: [{ type: Schema.Types.ObjectId, ref: 'Post' }],
  },
  {
    timestamps: true,          // Auto-add createdAt, updatedAt
    toJSON: { virtuals: true },
    toObject: { virtuals: true },
  }
);
```

### Middleware (Pre/Post Hooks)

```typescript
// Pre-save: hash password before writing
userSchema.pre('save', async function (next) {
  if (!this.isModified('password')) return next();
  this.password = await bcrypt.hash(this.password, 12);
  next();
});

// Pre-find: auto-exclude soft-deleted documents
userSchema.pre(/^find/, function (next) {
  // 'this' refers to the query object
  this.where({ deletedAt: { $exists: false } });
  next();
});

// Post-save: send welcome email
userSchema.post('save', async function (doc) {
  if (doc.isNew) {
    await sendWelcomeEmail(doc.email);
  }
});

// Pre-deleteOne: cascade delete related documents
userSchema.pre('deleteOne', { document: true, query: false }, async function () {
  await mongoose.model('Post').deleteMany({ author: this._id });
});

// Error handling middleware
userSchema.post('save', function (error: any, doc: IUser, next: Function) {
  if (error.name === 'MongoServerError' && error.code === 11000) {
    next(new Error('Email already exists'));
  } else {
    next(error);
  }
});
```

### Virtual Properties and Populate

```typescript
// Virtual: computed property, not stored in DB
userSchema.virtual('fullName').get(function () {
  return `${this.firstName} ${this.lastName}`;
});

// Virtual populate: get related docs without storing ObjectId array
userSchema.virtual('recentPosts', {
  ref: 'Post',
  localField: '_id',
  foreignField: 'author',
  options: { sort: { createdAt: -1 }, limit: 5 },
});

// Instance method
userSchema.methods.isAdult = function (): boolean {
  return (this.age ?? 0) >= 18;
};

// Static method
userSchema.statics.findByEmail = function (email: string) {
  return this.findOne({ email: email.toLowerCase() });
};

const User = mongoose.model<IUser, IUserModel>('User', userSchema);

// Populate: join-like behavior (executes separate queries)
const user = await User
  .findById(userId)
  .populate({
    path: 'posts',
    match: { published: true },      // Filter populated docs
    select: 'title createdAt',       // Select specific fields
    options: { sort: { createdAt: -1 }, limit: 10 },
    populate: { path: 'comments' },  // Nested populate (deep population)
  });

// GOTCHA: populate executes additional queries (not a DB-level JOIN).
// For N users each with M posts, this fires N+1 queries.
// For high-performance reads, use aggregation pipeline instead.
```

### Aggregation Pipeline

```typescript
// Complex analytics query using aggregation
const salesReport = await Order.aggregate([
  // Stage 1: Filter orders
  { $match: {
    status: 'completed',
    createdAt: { $gte: new Date('2025-01-01') },
  }},

  // Stage 2: Unwind array field (one doc per item)
  { $unwind: '$items' },

  // Stage 3: Lookup (LEFT JOIN equivalent)
  { $lookup: {
    from: 'products',
    localField: 'items.productId',
    foreignField: '_id',
    as: 'product',
  }},
  { $unwind: '$product' },

  // Stage 4: Group and aggregate
  { $group: {
    _id: {
      category: '$product.category',
      month: { $month: '$createdAt' },
    },
    totalRevenue: { $sum: { $multiply: ['$items.quantity', '$items.price'] } },
    orderCount: { $sum: 1 },
    avgOrderValue: { $avg: { $multiply: ['$items.quantity', '$items.price'] } },
  }},

  // Stage 5: Reshape output
  { $project: {
    _id: 0,
    category: '$_id.category',
    month: '$_id.month',
    totalRevenue: { $round: ['$totalRevenue', 2] },
    orderCount: 1,
    avgOrderValue: { $round: ['$avgOrderValue', 2] },
  }},

  // Stage 6: Sort
  { $sort: { totalRevenue: -1 } },
]);

// IMPORTANT: Aggregation pipeline runs in the database engine.
// Much faster than loading documents into Node.js and processing in JS.
// Use $match early to reduce documents flowing through the pipeline.
// Create indexes that support your $match and $sort stages.
```

### Indexing Strategies

```typescript
// Compound index: supports queries on (status), (status, createdAt)
orderSchema.index({ status: 1, createdAt: -1 });

// Text index: full-text search
postSchema.index({ title: 'text', content: 'text' }, {
  weights: { title: 10, content: 1 },  // Title matches ranked higher
});

// TTL index: auto-delete documents after expiry
sessionSchema.index({ expiresAt: 1 }, { expireAfterSeconds: 0 });

// Partial index: index only matching documents (smaller, faster)
orderSchema.index(
  { customerId: 1, createdAt: -1 },
  { partialFilterExpression: { status: 'pending' } }
);

// Unique sparse index: unique constraint only on docs that have the field
userSchema.index({ phoneNumber: 1 }, { unique: true, sparse: true });

// Geospatial index
locationSchema.index({ coordinates: '2dsphere' });
```

### Connection Management

```typescript
import mongoose from 'mongoose';

// Connection with retry logic
async function connectWithRetry(uri: string, maxRetries = 5): Promise<void> {
  for (let attempt = 1; attempt <= maxRetries; attempt++) {
    try {
      await mongoose.connect(uri, {
        maxPoolSize: 10,           // Connection pool size
        minPoolSize: 2,            // Min idle connections
        serverSelectionTimeoutMS: 5000,
        socketTimeoutMS: 45000,
        bufferCommands: false,     // Fail immediately if not connected
        autoIndex: process.env.NODE_ENV !== 'production',  // Don't build indexes in prod
      });
      console.log('MongoDB connected');
      return;
    } catch (err) {
      console.error(`Connection attempt ${attempt} failed:`, err);
      if (attempt === maxRetries) throw err;
      await new Promise((r) => setTimeout(r, Math.min(1000 * 2 ** attempt, 30000)));
    }
  }
}

// Monitor connection events
mongoose.connection.on('disconnected', () => console.warn('MongoDB disconnected'));
mongoose.connection.on('error', (err) => console.error('MongoDB error:', err));

// Graceful shutdown
process.on('SIGTERM', async () => {
  await mongoose.connection.close();
  process.exit(0);
});
```

---

## 4. Query Optimization in Node.js Context

### N+1 Problem and DataLoader Pattern

The N+1 problem is especially common in GraphQL resolvers. A query for 50 users, each resolving their posts, fires 1 + 50 = 51 queries.

```typescript
// PROBLEM: N+1 in a GraphQL resolver
const resolvers = {
  User: {
    posts: async (user: User) => {
      // Called once per user in the parent list
      return prisma.post.findMany({ where: { authorId: user.id } });
    },
  },
};

// SOLUTION: DataLoader -- batches and deduplicates within a single tick
import DataLoader from 'dataloader';

// Create loader per request (not global -- avoids stale cache across requests)
function createLoaders() {
  return {
    postsByAuthor: new DataLoader<number, Post[]>(async (authorIds) => {
      // Single query for all requested author IDs
      const posts = await prisma.post.findMany({
        where: { authorId: { in: [...authorIds] } },
      });

      // Map results back to input order (DataLoader contract)
      const postsByAuthor = new Map<number, Post[]>();
      for (const post of posts) {
        const existing = postsByAuthor.get(post.authorId) ?? [];
        existing.push(post);
        postsByAuthor.set(post.authorId, existing);
      }

      return authorIds.map((id) => postsByAuthor.get(id) ?? []);
    }),
  };
}

// In GraphQL context
const resolvers = {
  User: {
    posts: (user: User, _: any, ctx: { loaders: ReturnType<typeof createLoaders> }) => {
      return ctx.loaders.postsByAuthor.load(user.id);
      // All .load() calls in the same tick are batched into ONE query
    },
  },
};
```

```
Without DataLoader:                 With DataLoader:

Query: users(first: 50)            Query: users(first: 50)
  -> SELECT * FROM users            -> SELECT * FROM users
  -> 50x SELECT * FROM posts        -> SELECT * FROM posts
     WHERE authorId = ?                WHERE authorId IN (1,2,...,50)

Total: 51 queries                   Total: 2 queries
```

### Connection Pooling (Single-Threaded Node.js)

Node.js is single-threaded for JavaScript execution but handles I/O concurrently via the event loop. While a query is in-flight at the database, Node.js can issue more queries. This means a single Node.js process can easily saturate a connection pool.

```
┌─────────────────────────────────────────────────┐
│           Node.js Event Loop                     │
│                                                  │
│  Request A ──→ query 1 (async, uses conn 1)     │
│  Request B ──→ query 2 (async, uses conn 2)     │
│  Request C ──→ query 3 (async, uses conn 3)     │
│  Request D ──→ waiting... (pool exhausted)       │
│                                                  │
│  conn 1 returns ──→ Request D gets conn 1       │
└─────────────────────────────────────────────────┘
                        │
                        ▼
          ┌──────────────────────┐
          │  Connection Pool     │
          │  (size: 3 in this    │
          │   example)           │
          ├──────────────────────┤
          │  conn 1: busy        │
          │  conn 2: busy        │
          │  conn 3: busy        │
          └──────────────────────┘
                        │
                        ▼
              ┌──────────────┐
              │  PostgreSQL  │
              └──────────────┘
```

**Pool sizing for Node.js**: Unlike Java (where each thread blocks on I/O), Node.js needs fewer connections. A pool of 10-20 connections per process is typically sufficient. More connections add overhead from PostgreSQL process management.

```typescript
// node-postgres (pg) pool configuration
import { Pool } from 'pg';

const pool = new Pool({
  host: process.env.DB_HOST,
  port: 5432,
  database: 'myapp',
  user: process.env.DB_USER,
  password: process.env.DB_PASS,
  max: 20,                     // Max connections in pool
  idleTimeoutMillis: 30000,    // Close idle connections after 30s
  connectionTimeoutMillis: 5000, // Error if no connection available in 5s
  statement_timeout: 30000,    // Kill queries running longer than 30s
});

// Monitor pool health
pool.on('error', (err) => {
  console.error('Unexpected pool error:', err);
});

// Log pool stats periodically
setInterval(() => {
  console.log({
    totalCount: pool.totalCount,
    idleCount: pool.idleCount,
    waitingCount: pool.waitingCount,  // Requests waiting for a connection
  });
}, 60000);
```

### Pagination Strategies

```typescript
// OFFSET-based: simple but degrades on deep pages
async function getPostsOffset(page: number, pageSize: number) {
  return prisma.post.findMany({
    skip: (page - 1) * pageSize,    // Must scan and discard these rows
    take: pageSize,
    orderBy: { createdAt: 'desc' },
  });
}
// Page 1000 with pageSize 20: DB scans 20,000 rows, discards 19,980

// CURSOR-based: consistent performance regardless of depth
async function getPostsCursor(cursor: number | undefined, pageSize: number) {
  return prisma.post.findMany({
    take: pageSize,
    ...(cursor ? {
      skip: 1,                     // Skip the cursor record itself
      cursor: { id: cursor },
    } : {}),
    orderBy: { createdAt: 'desc' },
  });
}
// Always scans exactly pageSize rows -- O(1) regardless of page depth

// Relay-style pagination response
interface PaginatedResult<T> {
  edges: { node: T; cursor: string }[];
  pageInfo: {
    hasNextPage: boolean;
    hasPreviousPage: boolean;
    startCursor: string | null;
    endCursor: string | null;
  };
}
```

### Batch Operations

```typescript
// Prisma: createMany for bulk inserts
await prisma.user.createMany({
  data: users,
  skipDuplicates: true,
});

// For very large datasets, chunk the inserts
async function batchInsert<T>(
  items: T[],
  batchSize: number,
  insertFn: (batch: T[]) => Promise<void>,
): Promise<void> {
  for (let i = 0; i < items.length; i += batchSize) {
    const batch = items.slice(i, i + batchSize);
    await insertFn(batch);
  }
}

await batchInsert(records, 1000, async (batch) => {
  await prisma.record.createMany({ data: batch });
});

// Raw SQL COPY for maximum PostgreSQL insert performance
import { pipeline } from 'stream/promises';
import { from as copyFrom } from 'pg-copy-streams';

const client = await pool.connect();
try {
  const stream = client.query(copyFrom(
    "COPY users(email, name) FROM STDIN WITH (FORMAT csv)"
  ));
  const fileStream = fs.createReadStream('users.csv');
  await pipeline(fileStream, stream);
} finally {
  client.release();
}
```

---

## 5. Redis & Caching

### Redis Data Structures

| Structure | Operations | Use Case |
|-----------|------------|----------|
| **String** | GET, SET, INCR, MGET | Cache values, counters, distributed locks |
| **Hash** | HGET, HSET, HGETALL, HINCRBY | Object storage, user sessions |
| **List** | LPUSH, RPUSH, LPOP, LRANGE, LLEN | Queues, recent activity feeds |
| **Set** | SADD, SREM, SMEMBERS, SINTER, SUNION | Tags, unique visitors, mutual friends |
| **Sorted Set** | ZADD, ZRANGE, ZRANK, ZRANGEBYSCORE | Leaderboards, rate limiting, priority queues |
| **Stream** | XADD, XREAD, XREADGROUP | Event sourcing, message queue with consumer groups |
| **HyperLogLog** | PFADD, PFCOUNT | Approximate unique counts (12KB max) |

### Redis with Node.js

```typescript
// ioredis -- preferred: cluster support, Lua scripting, pipelining
import Redis from 'ioredis';

const redis = new Redis({
  host: process.env.REDIS_HOST,
  port: 6379,
  password: process.env.REDIS_PASSWORD,
  db: 0,
  maxRetriesPerRequest: 3,
  retryStrategy: (times: number) => {
    if (times > 10) return null; // Stop retrying
    return Math.min(times * 200, 5000);
  },
  enableReadyCheck: true,
  lazyConnect: true,   // Don't connect until first command
});

// Cluster mode
const cluster = new Redis.Cluster([
  { host: 'node1', port: 6379 },
  { host: 'node2', port: 6379 },
  { host: 'node3', port: 6379 },
], {
  redisOptions: { password: process.env.REDIS_PASSWORD },
  scaleReads: 'slave',  // Read from replicas
});
// NOTE: Redis 5+ renamed 'slave' → 'replica'. ioredis still accepts 'slave' for backward compat but 'replica' or 'all' is preferred.
```

### Caching Strategies

```typescript
// Cache-Aside (Lazy Loading) -- most common pattern
async function getUser(userId: string): Promise<User> {
  const cacheKey = `user:${userId}`;

  // 1. Check cache
  const cached = await redis.get(cacheKey);
  if (cached) return JSON.parse(cached);

  // 2. Cache miss -- load from DB
  const user = await prisma.user.findUniqueOrThrow({
    where: { id: parseInt(userId) },
  });

  // 3. Write to cache with TTL
  await redis.set(cacheKey, JSON.stringify(user), 'EX', 3600); // 1 hour TTL

  return user;
}

// Write-Through -- update cache on every write
async function updateUser(userId: string, data: Partial<User>): Promise<User> {
  const user = await prisma.user.update({
    where: { id: parseInt(userId) },
    data,
  });

  // Synchronously update cache
  await redis.set(`user:${userId}`, JSON.stringify(user), 'EX', 3600);

  return user;
}

// Write-Behind (Write-Back) -- buffer writes, flush to DB periodically
// Useful for high-frequency updates (view counts, analytics)
async function incrementViewCount(postId: string): Promise<void> {
  // Write to Redis immediately (fast)
  await redis.hincrby('post:views', postId, 1);
  // A background worker periodically flushes to PostgreSQL
}

// Background flush worker
async function flushViewCounts(): Promise<void> {
  const views = await redis.hgetall('post:views');
  if (Object.keys(views).length === 0) return;

  // Batch update to DB
  await prisma.$transaction(
    Object.entries(views).map(([postId, count]) =>
      prisma.post.update({
        where: { id: parseInt(postId) },
        data: { viewCount: { increment: parseInt(count) } },
      })
    )
  );

  // Clear flushed counts
  await redis.del('post:views');
}
```

### Cache Invalidation Patterns

```typescript
// Pattern 1: Simple key deletion on write
async function updateProduct(id: string, data: UpdateData): Promise<void> {
  await prisma.product.update({ where: { id: parseInt(id) }, data });
  await redis.del(`product:${id}`);
  await redis.del('products:list');  // Invalidate list cache too
}

// Pattern 2: Tag-based invalidation -- track which keys relate to an entity
async function cacheWithTags(key: string, value: string, tags: string[]): Promise<void> {
  const pipeline = redis.pipeline();
  pipeline.set(key, value, 'EX', 3600);
  for (const tag of tags) {
    pipeline.sadd(`tag:${tag}`, key);  // Track keys per tag
    pipeline.expire(`tag:${tag}`, 7200);
  }
  await pipeline.exec();
}

async function invalidateByTag(tag: string): Promise<void> {
  const keys = await redis.smembers(`tag:${tag}`);
  if (keys.length > 0) {
    const pipeline = redis.pipeline();
    for (const key of keys) {
      pipeline.del(key);
    }
    pipeline.del(`tag:${tag}`);
    await pipeline.exec();
  }
}

// Pattern 3: Versioned keys -- no explicit invalidation needed
async function getProductCached(productId: string): Promise<Product> {
  const version = await redis.get(`product:${productId}:version`) ?? '0';
  const cacheKey = `product:${productId}:v${version}`;

  const cached = await redis.get(cacheKey);
  if (cached) return JSON.parse(cached);

  const product = await prisma.product.findUniqueOrThrow({
    where: { id: parseInt(productId) },
  });
  await redis.set(cacheKey, JSON.stringify(product), 'EX', 3600);
  return product;
}

// On update: just bump the version -- old key expires naturally
async function onProductUpdate(productId: string): Promise<void> {
  await redis.incr(`product:${productId}:version`);
}
```

### Pub/Sub for Real-Time Features

```typescript
// Publisher: broadcast events to all subscribers
const pub = new Redis();

async function publishEvent(channel: string, event: object): Promise<void> {
  await pub.publish(channel, JSON.stringify(event));
}

// Subscriber: listen for events
const sub = new Redis();

sub.subscribe('notifications', 'chat-messages', (err, count) => {
  console.log(`Subscribed to ${count} channels`);
});

sub.on('message', (channel: string, message: string) => {
  const event = JSON.parse(message);
  switch (channel) {
    case 'notifications':
      broadcastToWebSockets(event);
      break;
    case 'chat-messages':
      handleChatMessage(event);
      break;
  }
});

// IMPORTANT: A subscribed Redis client cannot execute other commands.
// Always use separate connections for pub/sub and regular commands.
// ioredis handles this automatically with its built-in subscriber support.
```

### Redis as Session Store

```typescript
import session from 'express-session';
import RedisStore from 'connect-redis';

const redisStore = new RedisStore({
  client: redis,
  prefix: 'sess:',
  ttl: 86400,           // 24 hours
  disableTouch: false,   // Reset TTL on every access
});

app.use(session({
  store: redisStore,
  secret: process.env.SESSION_SECRET!,
  resave: false,
  saveUninitialized: false,
  cookie: {
    secure: process.env.NODE_ENV === 'production',
    httpOnly: true,
    maxAge: 86400000,    // 24 hours in ms
    sameSite: 'lax',
  },
}));
```

### Redis Lua Scripting for Atomic Operations

```typescript
// Lua scripts execute atomically on the Redis server -- no race conditions
// Example: Rate limiter (sliding window) as an atomic operation

const rateLimitScript = `
  local key = KEYS[1]
  local limit = tonumber(ARGV[1])
  local window = tonumber(ARGV[2])
  local now = tonumber(ARGV[3])
  local window_start = now - window

  -- Remove expired entries
  redis.call('ZREMRANGEBYSCORE', key, '-inf', window_start)

  -- Count current entries
  local count = redis.call('ZCARD', key)

  if count < limit then
    -- Add current request
    redis.call('ZADD', key, now, now .. '-' .. math.random(1000000))
    redis.call('EXPIRE', key, window)
    return 1  -- Allowed
  else
    return 0  -- Denied
  end
`;

// Define the command for reuse (ioredis caches the script SHA)
redis.defineCommand('rateLimit', {
  numberOfKeys: 1,
  lua: rateLimitScript,
});

async function isAllowed(clientId: string, limit: number, windowSec: number): Promise<boolean> {
  const result = await (redis as any).rateLimit(
    `ratelimit:${clientId}`,
    limit,
    windowSec * 1000,
    Date.now()
  );
  return result === 1;
}

// Distributed lock using Lua (simplified Redlock)
const acquireLockScript = `
  if redis.call('SET', KEYS[1], ARGV[1], 'NX', 'PX', ARGV[2]) then
    return 1
  end
  return 0
`;

const releaseLockScript = `
  if redis.call('GET', KEYS[1]) == ARGV[1] then
    return redis.call('DEL', KEYS[1])
  end
  return 0
`;

async function withLock<T>(
  lockKey: string,
  ttlMs: number,
  fn: () => Promise<T>,
): Promise<T> {
  const lockValue = crypto.randomUUID();

  const acquired = await redis.eval(acquireLockScript, 1, lockKey, lockValue, ttlMs);
  if (!acquired) throw new Error(`Failed to acquire lock: ${lockKey}`);

  try {
    return await fn();
  } finally {
    await redis.eval(releaseLockScript, 1, lockKey, lockValue);
  }
}
```

---

## 6. Database Transactions & Consistency

### ACID Properties

| Property | Meaning | Node.js Relevance |
|----------|---------|-------------------|
| **Atomicity** | All operations succeed or all roll back | Use `$transaction` / `BEGIN...COMMIT` blocks |
| **Consistency** | DB moves from one valid state to another | Constraints enforced at DB level, not app level |
| **Isolation** | Concurrent transactions don't interfere | Understand isolation levels for race conditions |
| **Durability** | Committed data persists through crashes | Configure `synchronous_commit` in PostgreSQL |

### Isolation Levels

```
┌──────────────────────────────────────────────────────────────────────┐
│                    Isolation Levels Spectrum                         │
│                                                                     │
│  READ          READ           REPEATABLE       SERIALIZABLE         │
│  UNCOMMITTED   COMMITTED      READ                                  │
│  |             |              |                |                     │
│  v             v              v                v                     │
│  Dirty reads   Non-repeatable Phantom reads    No anomalies         │
│  allowed       reads allowed  allowed          Full isolation        │
│                                                                     │
│  <-------- More concurrency ---------- More safety ------------>    │
│  <-------- Better performance --------- Worse performance ------>   │
└──────────────────────────────────────────────────────────────────────┘
```

| Anomaly | Description | Example |
|---------|-------------|---------|
| **Dirty Read** | Read uncommitted data from another txn | Txn A writes price=50, Txn B reads price=50, Txn A rolls back |
| **Non-Repeatable Read** | Same row returns different values | Read balance=100, another txn commits balance=50, re-read shows 50 |
| **Phantom Read** | Same query returns different row sets | Count WHERE status='active' returns 10, another txn inserts, recount returns 11 |

```typescript
// Setting isolation level in Prisma interactive transaction
await prisma.$transaction(async (tx) => {
  // This transaction uses SERIALIZABLE isolation
  const account = await tx.account.findUnique({ where: { id: 1 } });
  // Guaranteed no other transaction can modify this account concurrently
  await tx.account.update({
    where: { id: 1 },
    data: { balance: account!.balance - amount },
  });
}, {
  isolationLevel: 'Serializable',
});

// Setting isolation level in raw pg
const client = await pool.connect();
try {
  await client.query('BEGIN');
  await client.query('SET TRANSACTION ISOLATION LEVEL SERIALIZABLE');
  // ... queries ...
  await client.query('COMMIT');
} catch (err) {
  await client.query('ROLLBACK');
  throw err;
} finally {
  client.release();
}
```

### Optimistic vs Pessimistic Locking

```typescript
// --- OPTIMISTIC LOCKING ---
// Use a version/timestamp column. Check it on update. Retry if stale.
// Best when conflicts are RARE (most reads, few concurrent writes).

// Prisma approach: manual version check
async function updateProductOptimistic(
  productId: number,
  data: UpdateData,
  expectedVersion: number,
  maxRetries = 3,
): Promise<Product> {
  for (let attempt = 0; attempt < maxRetries; attempt++) {
    const result = await prisma.product.updateMany({
      where: {
        id: productId,
        version: expectedVersion,  // Only update if version matches
      },
      data: {
        ...data,
        version: { increment: 1 },
      },
    });

    if (result.count > 0) {
      return prisma.product.findUniqueOrThrow({ where: { id: productId } });
    }

    // Version mismatch -- reload and retry
    const current = await prisma.product.findUniqueOrThrow({
      where: { id: productId },
    });
    expectedVersion = current.version;
  }

  throw new Error('Optimistic lock failed after max retries');
}

// --- PESSIMISTIC LOCKING ---
// Lock the row in the database. Other transactions wait.
// Best when conflicts are FREQUENT.

// Prisma: use raw query for SELECT ... FOR UPDATE
async function transferFunds(
  senderId: number,
  receiverId: number,
  amount: number,
): Promise<void> {
  await prisma.$transaction(async (tx) => {
    // Lock both accounts in consistent order to prevent deadlocks
    const [id1, id2] = senderId < receiverId
      ? [senderId, receiverId]
      : [receiverId, senderId];

    // SELECT FOR UPDATE acquires row-level exclusive locks
    const accounts = await tx.$queryRaw<Account[]>`
      SELECT * FROM accounts
      WHERE id IN (${id1}, ${id2})
      ORDER BY id
      FOR UPDATE
    `;

    const sender = accounts.find((a) => a.id === senderId)!;
    if (sender.balance < amount) {
      throw new Error('Insufficient funds');
    }

    await tx.account.update({
      where: { id: senderId },
      data: { balance: { decrement: amount } },
    });
    await tx.account.update({
      where: { id: receiverId },
      data: { balance: { increment: amount } },
    });
  });
}
```

### Distributed Transactions and Saga Pattern

Distributed transactions across multiple services are impractical in microservices (2PC is slow and fragile). The Saga pattern breaks a distributed transaction into a sequence of local transactions with compensating actions.

```
Saga: Place Order

Step 1           Step 2            Step 3            Step 4
┌──────────┐    ┌──────────┐     ┌──────────┐     ┌──────────┐
│ Create   │───→│ Reserve  │────→│ Process  │────→│ Confirm  │
│ Order    │    │ Inventory│     │ Payment  │     │ Order    │
│ (pending)│    │          │     │          │     │ (active) │
└──────────┘    └──────────┘     └──────────┘     └──────────┘
     │               │                │
     v               v                v              Compensation
┌──────────┐    ┌──────────┐     ┌──────────┐
│ Cancel   │<───│ Release  │<────│ Refund   │     (rollback on failure)
│ Order    │    │ Inventory│     │ Payment  │
└──────────┘    └──────────┘     └──────────┘
```

```typescript
// Orchestration-based Saga
interface SagaStep<T> {
  name: string;
  execute: (context: T) => Promise<T>;
  compensate: (context: T) => Promise<T>;
}

class SagaOrchestrator<T> {
  private steps: SagaStep<T>[] = [];
  private completedSteps: SagaStep<T>[] = [];

  addStep(step: SagaStep<T>): this {
    this.steps.push(step);
    return this;
  }

  async execute(initialContext: T): Promise<T> {
    let context = initialContext;

    for (const step of this.steps) {
      try {
        context = await step.execute(context);
        this.completedSteps.push(step);
      } catch (error) {
        console.error(`Saga step "${step.name}" failed:`, error);
        await this.rollback(context);
        throw new Error(`Saga failed at step "${step.name}": ${error}`);
      }
    }

    return context;
  }

  private async rollback(context: T): Promise<void> {
    // Compensate in reverse order
    for (const step of [...this.completedSteps].reverse()) {
      try {
        await step.compensate(context);
      } catch (err) {
        console.error(`Compensation for "${step.name}" failed:`, err);
        // Log for manual resolution -- do NOT throw
      }
    }
  }
}

// Usage
const orderSaga = new SagaOrchestrator<OrderContext>()
  .addStep({
    name: 'createOrder',
    execute: async (ctx) => {
      ctx.orderId = await orderService.create(ctx.items);
      return ctx;
    },
    compensate: async (ctx) => {
      await orderService.cancel(ctx.orderId!);
      return ctx;
    },
  })
  .addStep({
    name: 'reserveInventory',
    execute: async (ctx) => {
      ctx.reservationId = await inventoryService.reserve(ctx.items);
      return ctx;
    },
    compensate: async (ctx) => {
      await inventoryService.release(ctx.reservationId!);
      return ctx;
    },
  })
  .addStep({
    name: 'processPayment',
    execute: async (ctx) => {
      ctx.paymentId = await paymentService.charge(ctx.userId, ctx.total);
      return ctx;
    },
    compensate: async (ctx) => {
      await paymentService.refund(ctx.paymentId!);
      return ctx;
    },
  });

await orderSaga.execute({ userId: 'u1', items: [...], total: 99.99 });
```

### Connection Handling in Async Node.js

```typescript
// DANGER: Connection leak in async code
async function riskyQuery(): Promise<void> {
  const client = await pool.connect();
  // If this throws, client is never released -- connection leak!
  const result = await client.query('SELECT * FROM users WHERE id = $1', [1]);
  client.release();
}

// SAFE: Always use try/finally for manual connection management
async function safeQuery(): Promise<void> {
  const client = await pool.connect();
  try {
    await client.query('BEGIN');
    await client.query('UPDATE accounts SET balance = balance - $1 WHERE id = $2', [100, 1]);
    await client.query('UPDATE accounts SET balance = balance + $1 WHERE id = $2', [100, 2]);
    await client.query('COMMIT');
  } catch (err) {
    await client.query('ROLLBACK');
    throw err;
  } finally {
    client.release();  // Always return connection to pool
  }
}

// BETTER: Use pool.query() for single queries (auto-acquires and releases)
const result = await pool.query('SELECT * FROM users WHERE id = $1', [1]);
// Connection automatically returned to pool

// BEST: Use a transaction helper
async function withTransaction<T>(
  pool: Pool,
  fn: (client: PoolClient) => Promise<T>,
): Promise<T> {
  const client = await pool.connect();
  try {
    await client.query('BEGIN');
    const result = await fn(client);
    await client.query('COMMIT');
    return result;
  } catch (err) {
    await client.query('ROLLBACK');
    throw err;
  } finally {
    client.release();
  }
}

await withTransaction(pool, async (client) => {
  await client.query('UPDATE ...');
  await client.query('INSERT ...');
});
```

### Deadlock Detection and Prevention

```typescript
// DEADLOCK SCENARIO:
// Transaction A: UPDATE users WHERE id=1, then UPDATE users WHERE id=2
// Transaction B: UPDATE users WHERE id=2, then UPDATE users WHERE id=1
// Both wait for each other indefinitely.

// PREVENTION: Always acquire locks in a consistent order
async function transferBetweenUsers(
  userId1: number,
  userId2: number,
  amount: number,
): Promise<void> {
  // Sort IDs to ensure consistent lock ordering
  const [firstId, secondId] = userId1 < userId2
    ? [userId1, userId2]
    : [userId2, userId1];

  await prisma.$transaction(async (tx) => {
    // Always lock lower ID first -- prevents deadlocks
    await tx.$queryRaw`SELECT 1 FROM users WHERE id = ${firstId} FOR UPDATE`;
    await tx.$queryRaw`SELECT 1 FROM users WHERE id = ${secondId} FOR UPDATE`;

    // Now safe to update in any order
    await tx.user.update({
      where: { id: userId1 },
      data: { balance: { decrement: amount } },
    });
    await tx.user.update({
      where: { id: userId2 },
      data: { balance: { increment: amount } },
    });
  }, {
    timeout: 10000,  // Transaction timeout -- limits deadlock wait
  });
}

// PostgreSQL detects deadlocks automatically and terminates one transaction.
// Always handle the error and retry:
async function retryOnDeadlock<T>(
  fn: () => Promise<T>,
  maxRetries = 3,
): Promise<T> {
  for (let attempt = 1; attempt <= maxRetries; attempt++) {
    try {
      return await fn();
    } catch (err: any) {
      const isDeadlock = err.code === '40P01' // PostgreSQL deadlock_detected
        || err.message?.includes('deadlock');
      if (!isDeadlock || attempt === maxRetries) throw err;
      // Exponential backoff with jitter
      await new Promise((r) => setTimeout(r, Math.random() * 100 * attempt));
    }
  }
  throw new Error('Unreachable');
}
```

---

## 7. Migration Strategies

### Schema Migration Tools Comparison

| Tool | ORM | Approach | Rollback | Diffing |
|------|-----|----------|----------|---------|
| **Prisma Migrate** | Prisma | Auto-generated from schema diff | Manual (create down migration) | Schema <-> DB diff |
| **TypeORM Migrations** | TypeORM | CLI-generated or auto-sync | `migration:revert` | Entity <-> DB diff |
| **Drizzle Kit** | Drizzle | Generated from schema diff | Manual | Schema <-> DB diff |
| **Knex Migrations** | Knex (query builder) | Hand-written up/down | Built-in `down()` | Manual |
| **node-pg-migrate** | None (raw pg) | Hand-written SQL | Built-in `down` | Manual |
| **Flyway/Liquibase** | Any | Versioned SQL files | Manual (Flyway) / Built-in (LB) | N/A |

### Zero-Downtime Migrations

The expand-contract pattern ensures no deployment window requires downtime.

```
Phase 1: EXPAND (backward compatible)
┌──────────────────────────────────────────────────────────┐
│ ALTER TABLE users ADD COLUMN display_name VARCHAR(255);  │
│ -- Both old code (using 'name') and new code work       │
│ -- New column is nullable, no default required           │
└──────────────────────────────────────────────────────────┘
                          │
                          v
Phase 2: MIGRATE (backfill data)
┌──────────────────────────────────────────────────────────┐
│ -- Backfill in batches to avoid locking the table        │
│ UPDATE users SET display_name = name                     │
│ WHERE display_name IS NULL AND id BETWEEN $1 AND $2;     │
│ -- Deploy new code that writes to BOTH columns           │
└──────────────────────────────────────────────────────────┘
                          │
                          v
Phase 3: CONTRACT (remove old column)
┌──────────────────────────────────────────────────────────┐
│ -- Only after ALL app instances use new column           │
│ ALTER TABLE users DROP COLUMN name;                      │
│ -- This is a separate migration/deployment               │
└──────────────────────────────────────────────────────────┘
```

```typescript
// Knex migration example: zero-downtime column rename
// Migration 1: Add new column
export async function up(knex: Knex): Promise<void> {
  await knex.schema.alterTable('users', (table) => {
    table.string('display_name', 255).nullable();
  });
}

export async function down(knex: Knex): Promise<void> {
  await knex.schema.alterTable('users', (table) => {
    table.dropColumn('display_name');
  });
}

// Migration 2: Backfill (run as data migration)
export async function up(knex: Knex): Promise<void> {
  const batchSize = 5000;
  let affected = batchSize;

  while (affected === batchSize) {
    const result = await knex.raw(`
      UPDATE users
      SET display_name = name
      WHERE display_name IS NULL
      LIMIT ?
    `, [batchSize]);

    affected = result.rowCount ?? 0;
    // Yield to other operations between batches
    await new Promise((r) => setTimeout(r, 100));
  }
}

// Migration 3: Drop old column (deploy AFTER all code uses display_name)
export async function up(knex: Knex): Promise<void> {
  await knex.schema.alterTable('users', (table) => {
    table.dropColumn('name');
  });
}
```

### Dangerous Operations to Avoid

```sql
-- LOCKS TABLE (PostgreSQL < 11): Adding column with default
ALTER TABLE large_table ADD COLUMN status VARCHAR DEFAULT 'active';
-- PostgreSQL 11+ made this instant for non-volatile defaults

-- LOCKS TABLE: Creating index without CONCURRENTLY
CREATE INDEX idx_users_email ON users(email);
-- FIX: CREATE INDEX CONCURRENTLY idx_users_email ON users(email);
-- NOTE: CONCURRENTLY cannot run inside a transaction block

-- REWRITES TABLE: Changing column type
ALTER TABLE orders ALTER COLUMN amount TYPE NUMERIC(12,2);
-- FIX: Add new column, backfill, swap (expand-contract)

-- FULL TABLE SCAN: Adding NOT NULL constraint
ALTER TABLE users ALTER COLUMN email SET NOT NULL;
-- FIX (PostgreSQL 12+): Add CHECK constraint first, then set NOT NULL
ALTER TABLE users ADD CONSTRAINT email_not_null CHECK (email IS NOT NULL) NOT VALID;
ALTER TABLE users VALIDATE CONSTRAINT email_not_null;
ALTER TABLE users ALTER COLUMN email SET NOT NULL;
ALTER TABLE users DROP CONSTRAINT email_not_null;
```

### Data Migrations vs Schema Migrations

```typescript
// Schema migration: changes the structure (DDL)
// - Add/remove columns, tables, indexes, constraints
// - Should be fast and non-destructive
// - Checked into version control with the schema migration tool

// Data migration: changes the data (DML)
// - Backfill new columns, transform existing data
// - Can be slow on large tables -- MUST run in batches
// - Can be a separate script or part of the migration

// ANTI-PATTERN: Mixing heavy data migration into schema migration
// This blocks deployment because the migration must complete before the
// app starts. For large tables, this could take hours.

// CORRECT: Separate data migration as an async job
import { Queue, Worker } from 'bullmq';

const migrationQueue = new Queue('data-migrations');

// Enqueue the backfill job
await migrationQueue.add('backfill-display-names', {
  batchSize: 5000,
  startId: 0,
});

// Worker processes batches
new Worker('data-migrations', async (job) => {
  const { batchSize, startId } = job.data;

  const result = await prisma.$executeRaw`
    UPDATE users
    SET display_name = name
    WHERE display_name IS NULL AND id > ${startId}
    ORDER BY id
    LIMIT ${batchSize}
  `;

  if (result > 0) {
    // Get the last processed ID for the next batch
    const lastUser = await prisma.user.findFirst({
      where: { displayName: { not: null }, id: { gt: startId } },
      orderBy: { id: 'desc' },
      select: { id: true },
    });

    // Enqueue next batch
    await migrationQueue.add('backfill-display-names', {
      batchSize,
      startId: lastUser?.id ?? startId + batchSize,
    });
  }

  await job.updateProgress(100);
});
```

### Rollback Strategies

```typescript
// Strategy 1: Reversible migrations (maintain down function)
// Works for simple schema changes
export async function up(knex: Knex): Promise<void> {
  await knex.schema.createTable('audit_logs', (table) => {
    table.increments('id');
    table.string('action').notNullable();
    table.jsonb('payload');
    table.timestamp('created_at').defaultTo(knex.fn.now());
  });
}

export async function down(knex: Knex): Promise<void> {
  await knex.schema.dropTable('audit_logs');
}

// Strategy 2: Forward-only migrations (preferred for production)
// Instead of rolling back, create a NEW migration that undoes the change.
// Rollbacks are dangerous because:
// 1. Data may have been written to new columns/tables
// 2. Down migrations are rarely tested
// 3. Rollback under pressure is error-prone

// Strategy 3: Feature flags for safe rollout
// Deploy migration + new code behind a feature flag.
// If issues arise, disable the flag -- no schema rollback needed.
```

### Multi-Tenant Database Design

```
Strategy 1: Shared Database, Shared Schema (column-based isolation)
┌─────────────────────────────────────────┐
│  users table                            │
│  ┌──────┬───────────┬──────────────┐    │
│  │ id   │ tenant_id │ name         │    │  Every query includes
│  ├──────┼───────────┼──────────────┤    │  WHERE tenant_id = ?
│  │ 1    │ acme      │ Alice        │    │
│  │ 2    │ globex    │ Bob          │    │  Row-Level Security (RLS)
│  │ 3    │ acme      │ Charlie      │    │  enforces at DB level
│  └──────┴───────────┴──────────────┘    │
└─────────────────────────────────────────┘

Strategy 2: Shared Database, Separate Schemas (PostgreSQL schemas)
┌─────────────────────────────────────────┐
│  PostgreSQL Database                     │
│  ┌──────────────┐  ┌──────────────┐     │
│  │ acme schema  │  │ globex schema│     │  SET search_path = 'acme';
│  │  users       │  │  users       │     │  Better isolation
│  │  orders      │  │  orders      │     │  Shared infrastructure
│  └──────────────┘  └──────────────┘     │
└─────────────────────────────────────────┘

Strategy 3: Separate Databases (strongest isolation)
┌──────────────┐  ┌──────────────┐
│ acme_db      │  │ globex_db    │        Complete isolation
│  users       │  │  users       │        Independent scaling
│  orders      │  │  orders      │        Complex management
└──────────────┘  └──────────────┘        Connection pool per tenant
```

```typescript
// Strategy 1: Row-Level Security with Prisma

// PostgreSQL RLS policy (applied via migration)
// CREATE POLICY tenant_isolation ON users
//   USING (tenant_id = current_setting('app.tenant_id'));
// ALTER TABLE users ENABLE ROW LEVEL SECURITY;

// Prisma middleware to set tenant context on every query
prisma.$use(async (params, next) => {
  // Set tenant_id for every write operation
  if (['create', 'createMany'].includes(params.action)) {
    params.args.data.tenantId = getCurrentTenantId();
  }

  // Filter by tenant_id for every read operation
  if (['findMany', 'findFirst', 'findUnique', 'count'].includes(params.action)) {
    params.args.where = {
      ...params.args.where,
      tenantId: getCurrentTenantId(),
    };
  }

  return next(params);
});

// Strategy 2: Schema-per-tenant with dynamic connection
const tenantConnections = new Map<string, PrismaClient>();

function getPrismaForTenant(tenantId: string): PrismaClient {
  if (!tenantConnections.has(tenantId)) {
    const client = new PrismaClient({
      datasources: {
        db: { url: `${BASE_DB_URL}?schema=${tenantId}` },
      },
    });
    tenantConnections.set(tenantId, client);
  }
  return tenantConnections.get(tenantId)!;
}

// GOTCHA: Each PrismaClient maintains its own connection pool.
// With many tenants, this can exhaust database connections.
// Use PgBouncer or Prisma Accelerate as a connection multiplexer.
```

---

## 8. Common Senior Interview Questions

**Q: How do you choose between Prisma, TypeORM, and Drizzle?**
Consider team SQL proficiency, project size, and performance requirements. Prisma for rapid prototyping with excellent DX and type safety -- accept the schema language and code generation step. TypeORM if the team has Java/C# background and wants familiar patterns -- but be aware of known stability issues. Drizzle for SQL-literate teams that want maximum performance and minimum abstraction -- the SQL-like API means queries are predictable.

**Q: How do you handle the N+1 problem in a GraphQL API?**
Use DataLoader to batch and deduplicate database calls within a single request. DataLoader collects all `.load()` calls in the same event loop tick and executes a single batched query. Create a new DataLoader instance per request to avoid stale cache. For REST APIs, the N+1 problem is typically solved with eager loading (`include` in Prisma, `relations` in TypeORM).

**Q: When would you use Redis vs the database for caching?**
Redis when you need sub-millisecond reads, shared cache across multiple Node.js processes/pods, or advanced data structures (sorted sets for leaderboards, pub/sub for real-time). Database-level caching (materialized views, query cache) when the data is complex to reconstruct and the read pattern matches what the DB optimizes for. For single-process apps with moderate traffic, in-memory caching (node-cache, LRU cache) may be sufficient without Redis overhead.

**Q: Explain cursor-based vs offset-based pagination.**
Offset-based (`SKIP/OFFSET`) is simple but degrades on deep pages because the DB must scan and discard rows. Page 1000 with 20 items/page forces the DB to read 20,000 rows. Cursor-based uses a WHERE clause on an indexed column (`WHERE id > last_seen_id LIMIT 20`), always reading exactly the requested number of rows regardless of position. Cursor-based also handles concurrent inserts/deletes correctly (no skipped or duplicated items), while offset-based can miss items or show duplicates when data changes between page requests.

**Q: How do you perform zero-downtime database migrations?**
Use the expand-contract pattern. Phase 1 (expand): add new columns/tables as nullable with no constraints. Phase 2 (migrate): backfill data in batches, deploy code that writes to both old and new locations. Phase 3 (contract): after all instances use the new schema, remove old columns. Never combine schema changes with heavy data migrations in a single deployment. Use `CREATE INDEX CONCURRENTLY` to avoid locking. Test migrations against production-sized data to estimate duration.

**Q: How do you handle distributed transactions across microservices?**
Avoid distributed transactions (2PC is slow and fragile). Use the Saga pattern instead -- a sequence of local transactions where each step has a compensating action. If step 3 of 5 fails, compensations for steps 2 and 1 execute in reverse order. Implement as orchestration (central coordinator) or choreography (event-driven). Store saga state for observability and manual resolution of stuck sagas. Accept eventual consistency and design idempotent compensating actions.

**Q: What connection pool size should a Node.js application use?**
Node.js is single-threaded but handles I/O concurrently, so it can saturate a connection pool faster than you might expect. Start with 10-20 connections per process. Monitor `waitingCount` on the pool -- if requests frequently wait for connections, increase the pool or optimize query duration. Remember: (number of pods) * (pool size per pod) must be less than the database's `max_connections`. Use PgBouncer in transaction mode if you need to support more application instances than the database can handle in direct connections.

---

## 9. Postgres Internals (senior-level must-know)

> This section goes beyond ORM usage into the engine. Cross-read with [`theory/system_design/patterns/database-indexing.md`](../system_design/patterns/database-indexing.md) for index theory and [`theory/system_design/patterns/caching.md`](../system_design/patterns/caching.md) for cache interaction patterns. The Node-side throughput story lives in [`11-system-design.md`](./11-system-design.md).

### 9.1 MVCC (Multi-Version Concurrency Control)

Postgres never updates a tuple in place. Every `UPDATE` writes a **new row version** and marks the old one dead; every `DELETE` only marks the tuple dead. Readers never block writers and writers never block readers because each transaction sees a **snapshot** based on its `xmin`/`xmax` and the list of in-progress transactions.

```
Tuple header (simplified):
┌──────┬──────┬────────────────┐
│ xmin │ xmax │ row data ...   │
└──────┴──────┴────────────────┘
  │      │
  │      └── transaction that deleted/updated this version (0 = live)
  └── transaction that created this version

Visibility rule (per snapshot):
  visible  iff  xmin committed AND xmin < snapshot.xmax AND xmin not in snapshot.in_progress
               AND (xmax == 0 OR xmax aborted OR xmax > snapshot.xmax OR xmax in_progress)
```

**Bloat.** Dead tuples stay on heap pages until `VACUUM` marks their space reusable. Heavy `UPDATE`/`DELETE` workloads without autovacuum keeping up yield "bloat": tables and indexes grow, cache efficiency drops, seq scans scan garbage. Signs: `pg_stat_user_tables.n_dead_tup` climbs; `pgstattuple` reports >20% dead space.

**VACUUM vs VACUUM FULL vs autovacuum.**
- `VACUUM` — non-blocking, marks space reusable, updates visibility map. Does NOT shrink the file.
- `VACUUM FULL` — rewrites the table, takes an `ACCESS EXCLUSIVE` lock. Reclaims disk but freezes the table. Avoid in prod.
- `autovacuum` — background daemon, triggered by `autovacuum_vacuum_scale_factor * rows + threshold`. Tune `autovacuum_vacuum_cost_limit` upward on busy tables.
- `VACUUM (FREEZE)` — prevents transaction ID wraparound (32-bit `xid` can wrap every ~2B transactions). Wraparound = database shutdown in single-user mode to recover. Monitor `pg_stat_activity` for `autovacuum: VACUUM ... (to prevent wraparound)`.

**HOT updates** (Heap-Only Tuple) — if an `UPDATE` touches no indexed column AND the new version fits on the same page, Postgres skips index updates. Design narrow indexes and avoid updating indexed columns hot-path to benefit.

### 9.2 Isolation Levels in Postgres

Postgres has only three effective levels (`READ UNCOMMITTED` silently upgrades to `READ COMMITTED`).

| Level | Dirty Read | Non-Repeatable Read | Phantom Read | Serialization Anomaly |
|---|---|---|---|---|
| `READ COMMITTED` (default) | No | **Yes** | **Yes** | **Yes** |
| `REPEATABLE READ` (snapshot) | No | No | No (in PG) | **Yes** |
| `SERIALIZABLE` (SSI) | No | No | No | No |

**READ COMMITTED anomaly — lost update:**

```sql
-- T1
BEGIN;
SELECT balance FROM accounts WHERE id = 1;   -- reads 100
-- T2 commits UPDATE accounts SET balance = 80 WHERE id = 1;
UPDATE accounts SET balance = 100 - 30 WHERE id = 1;  -- writes 70, loses T2's -20
COMMIT;
```

Fix with `SELECT ... FOR UPDATE` or use `UPDATE ... SET balance = balance - 30` (atomic read-modify-write on the row), or raise isolation.

**REPEATABLE READ anomaly — write skew:**

```sql
-- Rule: at least one doctor must stay on call.
-- T1                                    -- T2
BEGIN ISOLATION LEVEL REPEATABLE READ;   BEGIN ISOLATION LEVEL REPEATABLE READ;
SELECT count(*) FROM oncall              SELECT count(*) FROM oncall
 WHERE on_call = true;  -- sees 2         WHERE on_call = true;  -- sees 2
UPDATE oncall SET on_call = false        UPDATE oncall SET on_call = false
 WHERE id = 1;                            WHERE id = 2;
COMMIT;                                   COMMIT;     -- both succeed, 0 left!
```

RR protects each row but not set-level invariants. `SERIALIZABLE` (SSI — Serializable Snapshot Isolation) detects the RW-dependency cycle and aborts one transaction with `SQLSTATE 40001` (serialization failure). Application MUST retry.

```typescript
// Retry wrapper for SERIALIZABLE
async function runSerializable<T>(fn: (tx: PrismaClient) => Promise<T>, retries = 5): Promise<T> {
  for (let i = 0; i < retries; i++) {
    try {
      return await prisma.$transaction(fn, { isolationLevel: 'Serializable' });
    } catch (e: any) {
      if (e.code === 'P2034' || e.meta?.code === '40001') continue; // serialization_failure
      throw e;
    }
  }
  throw new Error('serialization retries exhausted');
}
```

### 9.3 EXPLAIN vs EXPLAIN (ANALYZE, BUFFERS)

- `EXPLAIN` — planner estimates only. Fast, does not execute. Use to sanity-check plans for destructive queries.
- `EXPLAIN (ANALYZE, BUFFERS)` — actually runs the query, reports real times, row counts, and buffer hits/misses. Use BUFFERS to detect cache-cold hot paths.
- `EXPLAIN (ANALYZE, BUFFERS, VERBOSE, FORMAT JSON)` — machine-readable; pair with tools like [explain.depesz.com](https://explain.depesz.com) or [pev2](https://github.com/dalibo/pev2).

**Reading a plan — quick rules:**
1. Read **inside out, bottom up**. Children feed parents.
2. `actual rows` vs `rows=` estimate — a >10x mis-estimate means stale `ANALYZE`/bad stats; run `ANALYZE tablename` or raise `default_statistics_target`.
3. `Rows Removed by Filter: N` — index is not selective; predicate applied post-scan. Consider partial/covering index.
4. `Buffers: shared hit=X read=Y` — `read` means disk I/O. High `read` = cache miss; consider `pg_prewarm` or a larger `shared_buffers`.
5. Startup cost vs total cost — Limit queries care about startup cost; aggregates care about total.

**Red flags on large tables:**
- `Seq Scan` on >100k rows when a selective predicate exists → missing/unused index.
- `Nested Loop` with outer rows in the thousands and an inner index scan — the loop may be fine; but `Nested Loop` where BOTH sides are large and unindexed is catastrophic. Hash Join or Merge Join is usually what you want.
- `Sort` spilling to disk (`Sort Method: external merge Disk: N kB`) → raise `work_mem` for the session or add an index that provides ordering.
- `Rows Removed by Join Filter` in high numbers → join predicate not using index; rewrite to equi-join on indexed columns.

```sql
-- Example: find the plan hotspot
EXPLAIN (ANALYZE, BUFFERS)
SELECT p.*, u.email
FROM posts p JOIN users u ON u.id = p.author_id
WHERE p.published = true AND p.created_at > now() - interval '7 days'
ORDER BY p.created_at DESC LIMIT 20;
```

If the plan shows `Seq Scan on posts` with a filter on `published AND created_at`, a **partial index** fixes it:

```sql
CREATE INDEX CONCURRENTLY posts_recent_published_idx
  ON posts (created_at DESC)
  WHERE published = true;
```

### 9.4 Indexes: B-tree / GIN / GiST / BRIN / Hash

> Deeper theory lives in [`theory/system_design/patterns/database-indexing.md`](../system_design/patterns/database-indexing.md). Here we focus on **when each wins in Postgres**.

| Index | Data/Ops | Wins when | Loses when |
|---|---|---|---|
| **B-tree** | `=`, `<`, `>`, `BETWEEN`, `ORDER BY`, prefix `LIKE 'foo%'` | default, equality and range, sortable | full-text, array containment, geo |
| **Hash** | `=` only | rare; B-tree is usually as good and supports more | any non-equality query |
| **GIN** | `jsonb`, arrays, full-text `tsvector`, `pg_trgm` for `ILIKE '%foo%'` | "contains" queries, multi-value columns | high write throughput (GIN writes are slow; mitigate with `fastupdate=on` + pending list tuning) |
| **GiST** | geometry, ranges, exclusion constraints, kNN (`<->`) | spatial, range overlap, nearest-neighbor | simple equality (B-tree is faster) |
| **BRIN** | very large tables where physical order correlates with a column (time-series, append-only logs) | tiny index size on TB-scale tables | randomly ordered data |

**Partial index** — predicate in `WHERE`. Great for soft-delete patterns and "hot" subsets:

```sql
CREATE INDEX users_active_email_idx ON users (email) WHERE deleted_at IS NULL;
```

**Expression index** — index a function result:

```sql
CREATE INDEX users_lower_email_idx ON users (lower(email));
-- Required for:  SELECT ... WHERE lower(email) = $1
```

**Index-only scan** — Postgres returns columns from the index without heap lookup, IF the visibility map says the page is all-visible AND all needed columns are in the index. Use `INCLUDE` for non-key columns:

```sql
CREATE INDEX posts_author_covering_idx
  ON posts (author_id) INCLUDE (title, created_at);
```

Trade-off: covering indexes bloat faster (more columns to store) and harm HOT updates.

### 9.5 Locking

**Row-level locks** (taken implicitly by `UPDATE`/`DELETE` or explicitly):

```sql
-- Strongest: blocks other writers and FOR UPDATE readers
SELECT * FROM orders WHERE id = 42 FOR UPDATE;

-- Share: allows concurrent reads, blocks writes
SELECT * FROM orders WHERE id = 42 FOR SHARE;

-- FOR NO KEY UPDATE / FOR KEY SHARE — weaker, needed to avoid blocking FKs
```

**`SKIP LOCKED` — the queue-worker pattern.** Perfect for dequeueing jobs from a `jobs` table without a separate message broker:

```sql
-- Each worker atomically grabs one unclaimed job
UPDATE jobs
SET    status = 'running', claimed_at = now(), claimed_by = $1
WHERE  id = (
  SELECT id FROM jobs
  WHERE  status = 'queued'
  ORDER  BY priority DESC, created_at
  FOR UPDATE SKIP LOCKED
  LIMIT 1
)
RETURNING *;
```

`SKIP LOCKED` returns rows that aren't locked by another transaction — no thundering herd, no deadlocks, no broker required. Pair with `NOTIFY` for wakeups (but see PgBouncer caveat below).

**Advisory locks** — application-defined locks keyed by a `bigint`. Not tied to any row. Great for cross-process mutexes (cron leader, migration runner):

```sql
-- Try-lock: returns true if acquired
SELECT pg_try_advisory_lock(hashtext('nightly-reindex')::bigint);
-- Session-scoped; released on disconnect or pg_advisory_unlock.
-- Transaction-scoped variant: pg_advisory_xact_lock — auto-released on COMMIT/ROLLBACK.
```

Advisory locks are held on the **connection**. With PgBouncer transaction pooling, session-scoped advisory locks are dangerous — use `*_xact_*` variants only.

### 9.6 Connection Pooling with PgBouncer

Postgres forks a backend per connection (~10 MB each). 500 Node pods with 20 connections = 10,000 backends — Postgres will OOM. PgBouncer multiplexes N client connections onto M server connections.

**Pool modes:**

| Mode | Server conn. returned to pool on... | Safe for | Breaks |
|---|---|---|---|
| `session` | client disconnect | anything | scales poorly (1:1 basically) |
| `transaction` (most common) | `COMMIT`/`ROLLBACK` | most web apps | session state (see below) |
| `statement` | every statement | simplest short queries | multi-statement tx, anything stateful |

**What transaction pooling breaks:**

1. **`LISTEN`/`NOTIFY`** — subscriptions are session-scoped. In transaction mode your `LISTEN` sits on a server backend that's reassigned after the current transaction ends; you never receive notifications. Fixes: dedicate a session-mode pool for listeners, or connect directly (bypass pgbouncer) for the listener process, or use logical replication / an external broker.
2. **Session `SET`** — `SET search_path`, `SET TIME ZONE`, temp tables, prepared statements. State leaks to the next borrower or vanishes. Use `SET LOCAL` inside a transaction instead.
3. **Prepared statements** — the protocol-level `PREPARE`/`EXECUTE` (what node-postgres's `statement_name` uses) caches a plan on a specific backend. In transaction mode, the next transaction may land on a different backend that doesn't know the name → `prepared statement "S_1" does not exist`. Mitigations:
   - PgBouncer ≥ 1.21 supports **protocol-level prepared statements** (`max_prepared_statements > 0`) — PgBouncer tracks and re-prepares on each backend. Enable it.
   - Prisma: set `pgbouncer=true` in the connection URL; Prisma disables named prepared statements.
   - `node-postgres`: don't pass `name` to `query()` unless you've verified pgbouncer support, OR use `statement_cache_mode=none` style.
4. **Advisory session locks** (see 9.5).
5. **Cursors across transactions** — same story; keep cursors inside a single transaction.

Sizing rule of thumb: `pgbouncer default_pool_size ≈ 2 * CPU cores on Postgres`. Client-side Node pool can be much larger because it talks to pgbouncer, not PG.

### 9.7 Common Perf Mistakes in Node ORMs

| Trap | Symptom | Fix |
|---|---|---|
| **N+1** in Prisma/TypeORM | `findMany` then loop calling relation loader | Use `include`/`relations`, or DataLoader in GraphQL |
| **Over-fetching** | `SELECT *` on 50-column table when UI needs 3 | `select: {…}` in Prisma, `select()` in Drizzle |
| **Missing prepared statements** | Plan-time cost on every query, CPU spikes | Let the driver prepare; mind pgbouncer (§9.6) |
| **JSON(B) abuse** | Giant `jsonb` column scanned to filter on one field | Promote hot keys to real columns, or add GIN/expression index |
| **Implicit JSON serialization** on hot paths | CPU on `JSON.stringify` of huge payloads | Stream rows, paginate, use `COPY` for bulk |
| **Transactions held across network calls** | Long-running tx → autovacuum blocked, bloat, lock pileup | Never `await fetch(...)` inside a DB transaction |
| **Opening PrismaClient per request** | Connection storm | Singleton client per process; HMR-safe globalThis trick in dev |
| **`findFirst` for uniqueness checks** | Seq scan on non-indexed column | Index the column or use `findUnique` on a unique constraint |
| **`$queryRaw` with string interpolation** | SQL injection | Use tagged-template form `$queryRaw\`...\${val}\`` or `Prisma.sql` |
| **Counting rows for pagination** | `COUNT(*)` on huge table every request | Use cursor pagination or cached approximate count (`reltuples`) |

---

## 10. Practice & Drills

### 10.1 "Must Know" Checklist

- [ ] Explain MVCC, `xmin`/`xmax`, dead tuples, autovacuum, wraparound.
- [ ] State the anomaly each isolation level prevents; give a write-skew example; know `40001` retry handling.
- [ ] Read `EXPLAIN (ANALYZE, BUFFERS)` and name the 5 hotspots: Seq Scan on big table, Nested Loop with large sides, Sort to Disk, high `Rows Removed by Filter`, estimate vs actual skew.
- [ ] Choose B-tree vs GIN vs GiST vs BRIN vs Hash for a given query; write a partial index, an expression index, a covering `INCLUDE` index.
- [ ] Implement a job queue using `FOR UPDATE SKIP LOCKED`.
- [ ] Use advisory locks (session vs xact) and know why session advisory locks break under pgbouncer transaction pooling.
- [ ] Pick a PgBouncer pool mode; list what breaks in transaction mode (LISTEN, SET, prepared statements, session advisory locks).
- [ ] Size a connection pool: `pods * perPodPool <= postgres.max_connections`; add PgBouncer when it doesn't.
- [ ] Know `CREATE INDEX CONCURRENTLY`, expand/contract migrations, zero-downtime schema changes (see §7).
- [ ] Diagnose bloat (`n_dead_tup`, `pgstattuple`) and replication lag (`pg_stat_replication.replay_lag`).

### 10.2 Common Traps (Node/ORM)

1. Prisma `select` omitted → ships the whole row; `Buffers` on the plan doesn't show it but the wire does.
2. TypeORM lazy relations (`Promise<Post[]>`) silently issue queries in a `.map(...)` — classic N+1.
3. Drizzle's `with: { posts: true }` joins; forgetting it and calling a separate query loops.
4. `prisma.$transaction([...])` (array form): The array form IS atomic — queries run sequentially within a single BEGIN/COMMIT on one connection.
5. Holding a transaction across `await fetch(...)` → long-running tx blocks autovacuum; table bloats.
6. Using `name` on `pg.Client.query` through PgBouncer transaction mode without `max_prepared_statements` → random `prepared statement "S_1" does not exist`.
7. `LISTEN/NOTIFY` through PgBouncer transaction mode → silently drops notifications.
8. `new PrismaClient()` per request in a serverless handler → connection exhaustion; use Accelerate/Data Proxy or a singleton + pgbouncer.
9. Migrating a big table with `ALTER TABLE ... ADD COLUMN ... DEFAULT <non-constant>` on PG <11 → full table rewrite with `ACCESS EXCLUSIVE` lock.
10. `SERIALIZABLE` without a retry loop — first contention spike returns `40001` to users as 500s.

### 10.3 2-Minute Answer Drill (oral)

Answer each in <=2 minutes, out loud:

1. How does MVCC work and why does Postgres need VACUUM?
2. Give a write-skew example and say which isolation level fixes it.
3. You see `Seq Scan` on a 50M-row table in EXPLAIN. What do you check first?
4. Why would you pick BRIN over B-tree?
5. Design a Postgres-only job queue for 50 workers.
6. What breaks when you put `LISTEN/NOTIFY` behind PgBouncer transaction mode?
7. Difference between `pg_advisory_lock` and `pg_advisory_xact_lock`?
8. When is an index-only scan possible, and why might one be blocked despite a covering index?
9. In Prisma, how do you avoid N+1 on a list endpoint with nested relations?
10. Your connection pool is exhausted. Walk me through the diagnosis.

### 10.4 Query / Schema Drill (5 problems)

**Q1 — Design an index.** Query:
```sql
SELECT id, title FROM posts
WHERE  author_id = $1 AND published = true
ORDER  BY created_at DESC LIMIT 20;
```
What index makes this index-only? Write it. (Consider partial + `INCLUDE`.)

**Q2 — Rewrite the slow query.**
```sql
SELECT * FROM events WHERE date_trunc('day', created_at) = '2026-04-14';
```
Why is this slow? Rewrite.

**Q3 — Pick the isolation level.** You're implementing: "transfer $X from A to B only if A.balance >= X, otherwise fail, and both A and B must end with correct totals even under contention." Choose level; justify; write the Node retry wrapper.

**Q4 — Explain this plan.**
```
Nested Loop  (cost=0.86..12345.67 rows=100 width=40) (actual time=0.042..5432.110 rows=84213 loops=1)
  ->  Seq Scan on orders o  (rows=100 estimate, actual rows=842000)
  ->  Index Scan using users_pkey on users u  (actual rows=1 loops=842000)
```
Identify the two bugs. Prescribe a fix.

**Q5 — Resolve a deadlock.** Two workers deadlock updating `(account_id, counter)` in different orders. Give the fix (hint: ordering + `SELECT ... FOR UPDATE` + `SKIP LOCKED` where applicable).

*(Answers are intentionally left as an exercise — this is a drill sheet, not a cheat sheet.)*

### 10.5 Debugging Drill (5 scenarios)

1. **Slow query.** An endpoint's p99 just jumped from 80ms → 3s. `EXPLAIN (ANALYZE, BUFFERS)` shows correct plan, `shared read` high. What changed? (Hint: stats, `pg_stat_statements`, cache eviction after a batch job, bloat.)
2. **Pool exhaustion.** Prisma logs `Timed out fetching a new connection from the connection pool.` Walk the call tree: Node pool, pgbouncer pool, Postgres `max_connections`, long-running tx in `pg_stat_activity`.
3. **Bloated table.** `users` is 80GB on disk for 2M rows. Diagnose with `pgstattuple`, `pg_stat_user_tables`. Plan `VACUUM`/`pg_repack` without downtime.
4. **Replication lag.** Read replica is 30s behind. Causes: long-running query on replica blocks apply (`hot_standby_feedback`), big WAL from a batch `UPDATE`, network saturation. Outline triage.
5. **Prepared statement issue with PgBouncer.** Intermittent `prepared statement "S_3" does not exist` after scaling Node pods. Root-cause and three fixes (ordered by blast radius).

### 10.6 Timed Practice — 30-Minute Mock

Budget strictly; speak or write answers.

- **0:00–0:05** MVCC + VACUUM explained to a junior; include bloat and wraparound.
- **0:05–0:10** Write-skew scenario in SQL + Node retry wrapper for SERIALIZABLE.
- **0:10–0:15** Given a slow query on `orders(user_id, status, created_at)`, design three candidate indexes and pick one with reasoning.
- **0:15–0:20** Design a Postgres-backed job queue for 50 workers. Include DDL, dequeue SQL, and the Node worker loop (~30 lines of TS).
- **0:20–0:25** Explain PgBouncer modes; list what breaks in transaction mode; configure a Prisma connection string accordingly.
- **0:25–0:30** Self-review: what did you hand-wave? Note gaps and return to the Must-Know checklist.

### 10.7 Review Checklist

- [ ] I can explain MVCC and VACUUM without notes.
- [ ] I can write a `FOR UPDATE SKIP LOCKED` dequeue from memory.
- [ ] I know the exact `40001`/`P2034` retry code path.
- [ ] I can read `EXPLAIN (ANALYZE, BUFFERS)` and identify the hotspot in <60s.
- [ ] I can pick B-tree / GIN / GiST / BRIN / Hash / partial / expression / covering for a query.
- [ ] I can describe PgBouncer modes and what each breaks.
- [ ] I know `SET LOCAL` vs `SET` and why it matters under pooling.
- [ ] I know how to avoid N+1 in Prisma, TypeORM, and Drizzle (one sentence each).
- [ ] I can size a connection pool end-to-end (Node → PgBouncer → Postgres).
- [ ] I cross-checked [`../system_design/patterns/database-indexing.md`](../system_design/patterns/database-indexing.md), [`../system_design/patterns/caching.md`](../system_design/patterns/caching.md), and [`./11-system-design.md`](./11-system-design.md) before the interview.
