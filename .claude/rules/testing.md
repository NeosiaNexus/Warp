---
globs:
  - "**/src/test/**/*.java"
  - "**/src/integrationTest/**/*.java"
---

# Testing Rules

- JUnit 5 with `@DisplayName` on every test class and test method
- Group related tests with `@Nested` inner classes
- Structure: given/when/then — even without comments, the code should read this way
- Mock external dependencies with Mockito, but prefer real objects when cheap
- Integration tests (`src/integrationTest/`) can use Netty embedded channels and real protocol codecs
- Test names describe behavior, not implementation: "should reject invalid protocol version" not "testProtocolVersion"
- Always run `./gradlew test` after writing tests to verify they pass
