# 05. Supply Chain Security and Modern Java Crypto

## Supply chain security is now a core appsec topic

Do not treat it as "DevOps extra credit".

Your software supply chain includes:

- direct dependencies
- transitive dependencies
- container base images
- build runners
- artifact registries
- signing and deployment paths

Log4Shell, xz-utils, SolarWinds, and 3CX changed the baseline expectations.

## OWASP Top 10 2025 context

Two points worth remembering:

- A03:2025 is now `Software Supply Chain Failures`
- A08:2025 is `Software or Data Integrity Failures`

Interviewers may ask why those are separate. Good answer:

- A03 is about compromised or risky inputs to your build and delivery process
- A08 is broader integrity failure: unsigned updates, deserialization, trust-boundary failures, and tampered data paths

## SBOM

An SBOM is your inventory of what you shipped.

Main formats:

- CycloneDX
- SPDX

Maven example:

```xml
<plugin>
    <groupId>org.cyclonedx</groupId>
    <artifactId>cyclonedx-maven-plugin</artifactId>
    <version>2.8.0</version>
    <executions>
        <execution>
            <phase>package</phase>
            <goals>
                <goal>makeAggregateBom</goal>
            </goals>
        </execution>
    </executions>
</plugin>
```

## Dependency scanning

Core rule: make scanning continuous, not occasional.

Typical tools:

- OWASP Dependency-Check
- Snyk
- Trivy
- Grype
- Dependabot or Renovate

## SLSA

SLSA gives you a maturity model for build integrity.

- Level 1: scripted build with provenance
- Level 2: hosted build service with signed provenance
- Level 3: stronger isolation and non-forgeability expectations

The exact level matters less in interviews than whether you connect provenance, isolation, and artifact verification.

## Sigstore

Know the three names:

- Cosign: signing and verification CLI
- Fulcio: issues short-lived certs tied to identity
- Rekor: transparency log

Example:

```bash
cosign sign --yes ghcr.io/myorg/myapp@sha256:abc123...
cosign verify ghcr.io/myorg/myapp@sha256:abc123... \
  --certificate-identity "https://github.com/myorg/myapp/.github/workflows/release.yml@refs/heads/main" \
  --certificate-oidc-issuer "https://token.actions.githubusercontent.com"
```

## Hardened pipeline checklist

1. Pin dependency versions
2. Use an internal artifact proxy or mirror
3. Generate SBOMs
4. Scan dependencies and images
5. Sign artifacts
6. Verify signatures before deploy
7. Record provenance
8. Keep production admission checks simple and enforceable

## Java crypto: what changed recently

### Security Manager

JEP 486 permanently disabled the Security Manager in Java 24.

This means:

- do not propose it as a sandbox
- `System.setSecurityManager(...)` is no longer a viable design answer
- if an interviewer asks about sandboxing untrusted code, talk about process isolation, containers, VMs, or dedicated runtimes instead

### KDF API

- Previewed in Java 24 via JEP 478
- Final in Java 25 via JEP 510
- JDK-provided KDF algorithms today are HKDF variants

Practical meaning:

- `javax.crypto.KDF` is real and shipped
- HKDF is in the platform
- Argon2 is not shipped in the JDK yet

There is now an OpenJDK draft JEP for Argon2id (`JEP draft 8377081`), but it is still draft work as of April 2026.

### Post-quantum primitives

Java 24 delivered:

- ML-KEM via JEP 496
- ML-DSA via JEP 497

That means the platform has first-class post-quantum KEM and signature primitives available to applications.

### Hybrid post-quantum TLS

Important correction:

- JEP 527 is no longer "targeted to Java 26"
- the current JEP page lists it as completed for JDK 27

The key groups include:

- `X25519MLKEM768`
- `SecP256r1MLKEM768`
- `SecP384r1MLKEM1024`

## ML-DSA and JAR signing

Be careful with dates here.

- JEP 497 explicitly said JDK 24 did not add ML-DSA support to JAR signing or TLS because the surrounding standards were not yet ready.
- Work to add ML-DSA JAR signing exists in OpenJDK issue tracking, but do not present it as already shipped in Java 24 or 25.

## Symmetric encryption defaults

For general application encryption, the safe default answer is still:

- AES-GCM
- random 96-bit nonce/IV
- never reuse `(key, IV)`
- authenticate associated data where needed

```java
SecretKey key = KeyGenerator.getInstance("AES").generateKey();
byte[] iv = new byte[12];
SecureRandom random = SecureRandom.getInstanceStrong();
random.nextBytes(iv);

Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
byte[] ciphertext = cipher.doFinal(plaintext);
```

## Algorithms to avoid in interview answers

- ECB mode
- unauthenticated CBC as a default pattern
- DES / 3DES
- RC4
- SHA-1 for new signature designs
- raw password hashing with general-purpose digests

## `SecureRandom` nuance

Use `SecureRandom`, not `Random`, for anything security-sensitive.

`SecureRandom.getInstanceStrong()` is valid, but do not turn it into a superstition. The exact provider behavior is platform-specific and controlled by `securerandom.strongAlgorithms`.

## Interview shortcuts

- "Security Manager is gone in Java 24."
- "KDF is a standard API in Java 25, but the JDK ships HKDF, not Argon2."
- "Java 24 added ML-KEM and ML-DSA."
- "Hybrid PQ TLS is the migration pattern; the JEP now lands in JDK 27."
- "Supply-chain security means inventory, provenance, signing, and verification, not just CVE scans."

## Primary references

- OWASP Top 10 2025: <https://owasp.org/Top10/2025/>
- JEP 486: <https://openjdk.org/jeps/486>
- JEP 496: <https://openjdk.org/jeps/496>
- JEP 497: <https://openjdk.org/jeps/497>
- JEP 510: <https://openjdk.org/jeps/510>
- JEP 527: <https://openjdk.org/jeps/527>
- JEP draft 8377081 (Argon2): <https://openjdk.org/jeps/8377081>
- Java 25 KDF API: <https://docs.oracle.com/en/java/javase/25/docs/api/java.base/javax/crypto/KDF.html>
- Java security standard names: <https://docs.oracle.com/en/java/javase/24/docs/specs/security/standard-names.html>
