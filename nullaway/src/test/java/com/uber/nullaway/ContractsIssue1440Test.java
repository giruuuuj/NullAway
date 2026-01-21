package com.uber.nullaway;

import java.util.Arrays;
import org.junit.Test;

public class ContractsIssue1440Test extends NullAwayTestsBase {

  @Test
  public void testContractIssue1440PositiveCases() {
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:CheckContracts=true"))
        .addSourceLines(
            "TestContractIssue1440.java",
            """
            package com.uber;
            import javax.annotation.Nullable;
            import org.jetbrains.annotations.Contract;
            public class TestContractIssue1440 {
              
              // These should NOT produce errors - they correctly implement the contract
              @Contract("!null -> !null")
              public static @Nullable Integer of(@Nullable String text) {
                if (text != null) {
                  return Integer.parseInt(text);
                }
                else {
                  return null;
                }
              }
              
              @Contract("!null -> !null")
              public static @Nullable Integer of2(@Nullable String text) {
                if (text != null) {
                  return Integer.parseInt(text);
                }
                return null;
              }
              
              @Contract("!null -> !null")
              public static @Nullable Integer of3(@Nullable String text) {
                return (text != null) ? Integer.parseInt(text) : null;
              }
              
              // This SHOULD produce an error - violates the contract
              @Contract("!null -> !null")
              public static @Nullable Integer bad(@Nullable String text) {
                // BUG: Diagnostic contains: Method bad has @Contract("!null -> !null"), but this appears to be violated
                return null; // Always returns null, even when text is non-null
              }
              
              // This SHOULD produce an error - violates the contract
              @Contract("!null -> !null")
              public static @Nullable Integer bad2(@Nullable String text) {
                if (text != null) {
                  // BUG: Diagnostic contains: Method bad2 has @Contract("!null -> !null"), but this appears to be violated
                  return null; // Returns null when text is non-null
                }
                return Integer.parseInt("42");
              }
            }
            """)
        .doTest();
  }
}
