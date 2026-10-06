/**
 * JVM arguments of every JVM that runs Warp's unit tests: the test tasks' (warp.java-conventions)
 * and the PIT minions' (warp.pitest-conventions).
 */
val TEST_JVM_ARGS: List<String> = listOf(
    // Mockito attaches its inline mock maker as an agent at run time (JEP 451).
    "-XX:+EnableDynamicAgentLoading",
)
