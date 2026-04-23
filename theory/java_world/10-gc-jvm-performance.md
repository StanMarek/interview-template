# GC, JVM & Performance

This topic was split to keep the material readable and easier to maintain. Time-sensitive JVM, GC,
JFR, and JEP claims were reviewed against OpenJDK JEP pages and current Oracle/OpenJDK docs as of
April 2026.

## Parts

1. [GC Fundamentals and Algorithms](./10-gc-jvm-performance/01-gc-fundamentals-and-algorithms.md)
2. [G1 Garbage Collector](./10-gc-jvm-performance/02-g1-garbage-collector.md)
3. [ZGC and Shenandoah](./10-gc-jvm-performance/03-zgc-and-shenandoah.md)
4. [GC Tuning and Memory Leaks](./10-gc-jvm-performance/04-gc-tuning-and-memory-leaks.md)
5. [JFR, JMC, and Diagnostics](./10-gc-jvm-performance/05-jfr-jmc-and-diagnostics.md)
6. [JIT, Graal, Leyden, and Object Layout](./10-gc-jvm-performance/06-jit-graal-leyden-and-object-layout.md)
7. [Performance Pitfalls and JMH](./10-gc-jvm-performance/07-performance-pitfalls-and-jmh.md)
8. [Off-Heap Memory, Valhalla, and Interview Questions](./10-gc-jvm-performance/08-off-heap-valhalla-and-interview-questions.md)

## Current Baseline

- CMS was removed in JDK 14.
- G1 remains the default collector.
- ZGC became generational by default in JDK 23 and generational-only in JDK 24.
- Generational Shenandoah became a product feature in JDK 25, but it remains opt-in and is not
  shipped in Oracle JDK builds.

Use JDK 21 and JDK 25 semantics as the default interview baseline unless a section explicitly calls
out older or newer behavior.
