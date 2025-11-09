# Phase 0: Pre-Implementation Setup

**Timeline**: Day 0 (before Phase 1)
**Status**: Not Started
**Priority**: P0 (Blocking)

---

## Overview

This phase establishes the prerequisites for debugger implementation:
- Add required dependencies (RxJava, Janino)
- Document JDK version requirements and JVM flags
- Add security warnings and usage guidelines
- Prepare test file structure
- Validate build configuration

**Success Criteria**:
- All dependencies added to pom.xml
- Documentation updated with requirements and warnings
- Build succeeds with new dependencies
- Team understands security implications
- Ready to begin Phase 1 implementation

---

## Task 0.1: Add Dependencies

**Estimated Time**: 1 hour
**Assignee**: TBD
**Dependencies**: None

### Description

Add RxJava and Janino dependencies to the project for EventHub and expression evaluation capabilities.

### Subtasks

1. **Add RxJava 3.x dependency** to `pom.xml`:
   ```xml
   <dependency>
     <groupId>io.reactivex.rxjava3</groupId>
     <artifactId>rxjava</artifactId>
     <version>3.1.8</version>
   </dependency>
   ```
   - **Purpose**: EventHub event streaming (Phase 1, Task 1.4)
   - **License**: Apache 2.0 (compatible with project)
   - **JDK Compatibility**: JDK 8+ (no issues with JDK 11+ target)

2. **Add Janino compiler dependency** to `pom.xml`:
   ```xml
   <dependency>
     <groupId>org.codehaus.janino</groupId>
     <artifactId>janino</artifactId>
     <version>3.1.11</version>
   </dependency>
   ```
   - **Purpose**: Expression compilation (Phase 4, Task 4.2)
   - **License**: BSD 3-Clause (compatible with project)
   - **JDK Compatibility**: JDK 11+ features supported

3. **Verify build** with new dependencies:
   ```bash
   mvn clean compile
   ```

### Acceptance Criteria

- [ ] RxJava 3.1.8 added to pom.xml dependencies
- [ ] Janino 3.1.11 added to pom.xml dependencies
- [ ] Build completes successfully
- [ ] Dependencies downloaded and cached
- [ ] No version conflicts with existing dependencies

### Notes

- Both dependencies are widely used and well-maintained
- RxJava is needed immediately in Phase 1
- Janino is not needed until Phase 4 but added now for completeness
- ASM is already a dependency (for hot reload), no additional bytecode libraries needed

---

## Task 0.2: Document JDK Requirements

**Estimated Time**: 1 hour
**Assignee**: TBD
**Dependencies**: None

### Description

Document the minimum JDK version and required JVM flags for debugger functionality.

### Subtasks

1. **Add JDK version requirement** to README.md:
   - Minimum: **JDK 11+** for debugger feature
   - Rationale: Clean self-attach API, stable JDI/JDWP
   - Note: Project configured for JDK 23 but debugger works on JDK 11+

2. **Document required JVM flags** in README.md:

   **For JDK 11-16**:
   ```bash
   # Required for self-debugging (attach to own process)
   -Djdk.attach.allowAttachSelf=true

   # Required for module access
   --add-modules jdk.attach,jdk.jdi
   ```

   **For JDK 17+ (Additional flags required)**:
   ```bash
   # Required for self-debugging
   -Djdk.attach.allowAttachSelf=true

   # Required for module access
   --add-modules jdk.attach,jdk.jdi

   # CRITICAL: Required for Attach API reflection access (JDK 17+ only)
   --add-opens jdk.attach/sun.tools.attach=ALL-UNNAMED
   ```

   **Why JDK 17+ needs --add-opens**:
   - JDK 17 introduced stronger module encapsulation
   - The Attach API uses reflection to access internal classes
   - Without this flag, you'll get `InaccessibleObjectException`
   - This is a JPMS (Java Platform Module System) requirement

3. **Add to Maven execution examples**:

   **For JDK 11-16**:
   ```bash
   # Run with debugger support
   mvn exec:java -Djdk.attach.allowAttachSelf=true
   ```

   **For JDK 17+**:
   ```bash
   # Run with debugger support (JDK 17+ requires --add-opens)
   mvn exec:java \
     -Djdk.attach.allowAttachSelf=true \
     -Dexec.args="--add-modules jdk.attach,jdk.jdi --add-opens jdk.attach/sun.tools.attach=ALL-UNNAMED"

   # Or with agent (for hot reload + debugger)
   mvn compile exec:exec -Prun-with-agent \
     -Djdk.attach.allowAttachSelf=true \
     -Dexec.args="--add-opens jdk.attach/sun.tools.attach=ALL-UNNAMED"
   ```

4. **Document JDK compatibility matrix**:
   | JDK Version | Debugger Support | Required Flags | Notes |
   |-------------|------------------|----------------|-------|
   | JDK 8-10 | ❌ Not Supported | N/A | Self-attach unreliable |
   | JDK 11-16 | ✅ Supported | `--add-modules` only | Minimum version, stable |
   | JDK 17-20 | ✅ Supported | `--add-modules` + `--add-opens` | **JPMS restrictions** |
   | JDK 21-22 | ✅ Supported | `--add-modules` + `--add-opens` | LTS release (21) |
   | JDK 23+ | ✅ Fully Supported | `--add-modules` + `--add-opens` | Project target |

   **IMPORTANT**: JDK 17+ is now the recommended LTS version. Always include `--add-opens` flags when targeting JDK 17+.

### Acceptance Criteria

- [ ] README.md updated with JDK 11+ minimum requirement
- [ ] JVM flags documented clearly
- [ ] Maven execution examples include required flags
- [ ] Compatibility matrix added
- [ ] Link to JDK self-attach documentation provided

### Notes

- The JDWPConnector (Phase 1, Task 1.3) will validate JDK version at runtime
- Clear error message if JDK < 11 detected
- Virtual threads (JDK 21+) supported but not required

---

## Task 0.3: Add Security Warnings

**Estimated Time**: 1.5 hours
**Assignee**: TBD
**Dependencies**: None

### Description

Document security implications of debugger functionality to prevent misuse in production environments.

### Subtasks

1. **Add prominent security warning** to README.md:
   ```markdown
   ## ⚠️ SECURITY WARNING - Debugger Feature

   The debugger tools provide **arbitrary code execution** capabilities through:
   - **Expression evaluation**: Can execute any Java code in the target JVM
   - **JShell REPL access**: Full scripting access to running application
   - **JDWP protocol**: Complete JVM control (read/modify memory, change execution flow)

   ### NEVER expose Descartes MCP server to:
   - ❌ Production servers or environments
   - ❌ Internet-accessible networks
   - ❌ Multi-tenant systems
   - ❌ Untrusted users or clients

   ### Safe usage:
   - ✅ Local development with Claude Code
   - ✅ Isolated development containers
   - ✅ Secure CI/CD environments (testing only)
   - ✅ Debugging sessions on developer workstations

   **The debugger is a DEVELOPMENT TOOL ONLY.**
   ```

2. **Document JDWP security implications**:
   - Self-attach opens local JDWP connection (loopback only)
   - No external JDWP port opened by default
   - But MCP tools expose JDWP capabilities over MCP protocol
   - MCP server security = debugger security boundary

3. **Add expression evaluation warning**:
   ```markdown
   ### Expression Evaluation Security

   The `debugger_evaluate` tool can execute arbitrary Java expressions:
   ```java
   // Examples of what's possible:
   evaluator.evaluate("System.exit(1)")  // Crash the JVM
   evaluator.evaluate("new File('/etc/passwd').delete()")  // File operations
   evaluator.evaluate("Runtime.getRuntime().exec('rm -rf /')")  // System commands
   ```

   **Only use in trusted development environments.**
   ```

4. **Add to CLAUDE.md** project instructions:
   ```markdown
   ## Debugger Security Guidelines for Claude

   When using Descartes debugger tools:
   - Verify you're in a development environment
   - Never debug production applications
   - Exercise caution with expression evaluation
   - Limit expression evaluation to inspection (getters, toString)
   - Avoid side-effecting operations unless explicitly requested
   ```

### Acceptance Criteria

- [ ] Prominent security warning in README.md
- [ ] JDWP security implications documented
- [ ] Expression evaluation risks explained with examples
- [ ] CLAUDE.md updated with guidelines for AI assistant usage
- [ ] Warning visible before "Getting Started" section

### Notes

- Security is paramount - make warnings impossible to miss
- Balance between usability and responsibility
- Document acceptable use cases clearly
- Consider adding runtime safety checks (future enhancement)

---

## Task 0.4: Prepare Test File Structure

**Estimated Time**: 0.5 hours
**Assignee**: TBD
**Dependencies**: None

### Description

Prepare test file structure for debugger implementation, temporarily moving incomplete test files.

### Subtasks

1. **Move ExpressionParserTest.java** temporarily:
   ```bash
   # Currently at: src/test/java/com/bitsapplied/descartes/debugger/expression/parser/ExpressionParserTest.java
   # Move to: src/test/resources/planning/expression-parser-test.java

   # This file references non-existent classes (will be implemented in Phase 4)
   # Moving prevents build failures during Phases 1-3
   ```

2. **Add comment to moved file**:
   ```java
   /*
    * Phase 4 Implementation - Expression Parser Tests
    *
    * This test file is temporarily moved to avoid build failures.
    * It will be moved back to the test source tree when Phase 4 begins.
    *
    * Missing classes (to be implemented):
    * - ExpressionParser
    * - ExpressionNode and subtypes
    * - ExpressionParseException
    *
    * See: planning/debugger/tasks/04-expressions.md
    */
   ```

3. **Create placeholder directories**:
   ```bash
   mkdir -p src/main/java/com/bitsapplied/descartes/debugger
   mkdir -p src/test/java/com/bitsapplied/descartes/debugger
   ```
   - Prevents IDE warnings
   - Signals debugger package structure

4. **Update .gitignore** (if needed):
   - Ensure test resources are tracked
   - No debugger-specific ignores needed yet

### Acceptance Criteria

- [ ] ExpressionParserTest.java moved to test resources
- [ ] Comment added explaining temporary location
- [ ] Placeholder debugger directories created
- [ ] Build succeeds (no compilation errors)
- [ ] Git tracks moved file

### Notes

- File will be moved back at start of Phase 4
- This is test-driven development - tests written before implementation
- Prevents breaking existing CI/CD pipeline

---

## Task 0.5: Validate Build Configuration

**Estimated Time**: 0.5 hours
**Assignee**: TBD
**Dependencies**: Tasks 0.1, 0.4

### Description

Validate that build configuration is correct and all prerequisites are met for debugger implementation.

### Subtasks

1. **Run full build**:
   ```bash
   mvn clean install
   ```
   - Should complete successfully
   - All existing tests pass
   - New dependencies downloaded

2. **Run existing tools**:
   ```bash
   mvn exec:java -Djdk.attach.allowAttachSelf=true
   ```
   - Server starts on port 9080
   - All existing tools registered
   - No errors or warnings

3. **Verify JDK version**:
   ```bash
   java -version
   # Should show JDK 11+
   ```

4. **Test with self-attach flag**:
   ```bash
   java -Djdk.attach.allowAttachSelf=true -jar target/descartes-mcp-*-jar-with-dependencies.jar
   ```
   - Validates flag is recognized
   - No errors about attach API

5. **Check RxJava availability**:
   ```bash
   mvn dependency:tree | grep rxjava
   ```
   - Should show `io.reactivex.rxjava3:rxjava:3.1.8`

6. **Check Janino availability**:
   ```bash
   mvn dependency:tree | grep janino
   ```
   - Should show `org.codehaus.janino:janino:3.1.11`

### Acceptance Criteria

- [ ] Full build succeeds
- [ ] All existing tests pass
- [ ] Server runs with self-attach flag
- [ ] RxJava and Janino in dependency tree
- [ ] JDK version >= 11
- [ ] No Maven warnings or errors

### Notes

- This validates readiness for Phase 1
- Any issues should be resolved before starting implementation
- Document any environment-specific configuration needed

---

## Summary

**Total Estimated Time**: 4 hours

**Dependencies Added**:
- RxJava 3.1.8 (Apache 2.0 license)
- Janino 3.1.11 (BSD 3-Clause license)

**Documentation Updates**:
- JDK 11+ minimum requirement
- Required JVM flags
- Security warnings (JDWP + expression evaluation)
- Compatibility matrix

**File Changes**:
- pom.xml (2 new dependencies)
- README.md (requirements, security, usage)
- CLAUDE.md (security guidelines)
- ExpressionParserTest.java (moved temporarily)

**Validation**:
- Build succeeds with new dependencies
- Existing functionality unaffected
- Environment ready for Phase 1

---

## Ready for Phase 1

After completing Phase 0:
- ✅ All dependencies installed
- ✅ Documentation complete
- ✅ Security implications understood
- ✅ Build validated
- ✅ Team can begin implementation

**Next**: Phase 1 - Foundation (Weeks 1-2, 60 hours)
