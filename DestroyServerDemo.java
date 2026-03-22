/**
 * Demonstrates the destroyServer() exception handling gap and fix.
 *
 * The bug: destroyServer() catches IOException but NOT IllegalStateException.
 * When Hetzner returns HTTP 429 (rate limit) or 412 (precondition failed),
 * assertValidResponse() throws IllegalStateException which bypasses the
 * IOException catch. In percona.3, _terminate() catches it one level up.
 * In percona.4, destroyServer() itself catches all exceptions.
 *
 * Usage:
 *   javac DestroyServerDemo.java && java DestroyServerDemo
 */
public class DestroyServerDemo {

    // Simulated Hetzner API responses
    enum ApiResponse {
        OK_200,
        RATE_LIMITED_429,
        PRECONDITION_FAILED_412,
        NETWORK_ERROR,
    }

    static class IOException extends Exception {
        IOException(String msg) { super(msg); }
    }

    static class IllegalStateException extends RuntimeException {
        IllegalStateException(String msg) { super(msg); }
    }

    // Simulated assertValidResponse (from Helper.java)
    static void assertValidResponse(ApiResponse response) throws IOException {
        if (response == ApiResponse.NETWORK_ERROR) {
            throw new IOException("Network error: connection refused");
        }
        if (response != ApiResponse.OK_200) {
            // This is the bug: Preconditions.checkState throws IllegalStateException
            // (unchecked) which is NOT caught by "catch (IOException e)"
            throw new IllegalStateException("Invalid API response: HTTP " + switch (response) {
                case RATE_LIMITED_429 -> "429 Too Many Requests";
                case PRECONDITION_FAILED_412 -> "412 Precondition Failed";
                default -> response.toString();
            });
        }
    }

    // -----------------------------------------------------------------------
    // BUGGY destroyServer (percona.3 - catches IOException only)
    // -----------------------------------------------------------------------

    static String destroyServerBuggy(long serverId, ApiResponse powerOffResponse,
                                      ApiResponse deleteResponse) {
        try {
            assertValidResponse(powerOffResponse);
            System.out.printf("    [buggy] Server %d powered off%n", serverId);

            // Simulate polling (simplified)
            assertValidResponse(deleteResponse);
            System.out.printf("    [buggy] Server %d deleted%n", serverId);
            return "OK";

        } catch (IOException e) {
            // percona.3: only catches IOException
            // IllegalStateException from HTTP 429/412 ESCAPES here!
            System.out.printf("    [buggy] Caught IOException: %s%n", e.getMessage());
            return "CAUGHT_IO:" + e.getMessage();
        }
        // IllegalStateException propagates to _terminate() or CRW timer
    }

    // -----------------------------------------------------------------------
    // FIXED destroyServer (percona.4 - catches all exceptions)
    // -----------------------------------------------------------------------

    static String destroyServerFixed(long serverId, ApiResponse powerOffResponse,
                                      ApiResponse deleteResponse) {
        try {
            assertValidResponse(powerOffResponse);
            System.out.printf("    [fixed] Server %d powered off%n", serverId);

            assertValidResponse(deleteResponse);
            System.out.printf("    [fixed] Server %d deleted%n", serverId);
            return "OK";

        } catch (Exception e) {
            // percona.4: catches ALL exceptions (IOException AND IllegalStateException)
            // Server becomes orphaned, OrphanedNodesCleaner retries in ~1 hour
            System.out.printf("    [fixed] Caught %s: %s (will retry via OrphanedNodesCleaner)%n",
                e.getClass().getSimpleName(), e.getMessage());
            return "CAUGHT:" + e.getMessage();
        }
    }

    // -----------------------------------------------------------------------
    // Simulated _terminate() wrapper
    // -----------------------------------------------------------------------

    static String terminateBuggy(long serverId, ApiResponse powerOff, ApiResponse delete) {
        try {
            return destroyServerBuggy(serverId, powerOff, delete);
        } catch (Exception e) {
            // percona.3's _terminate() catch-all saves CRW, but the exception
            // already escaped destroyServer without logging the server details
            System.out.printf("    [_terminate] Caught escaped %s: %s%n",
                e.getClass().getSimpleName(), e.getMessage());
            return "ESCAPED_TO_TERMINATE:" + e.getMessage();
        }
    }

    static String terminateFixed(long serverId, ApiResponse powerOff, ApiResponse delete) {
        try {
            return destroyServerFixed(serverId, powerOff, delete);
        } catch (Exception e) {
            // percona.4: this should never fire for destroyServer exceptions
            System.out.printf("    [_terminate] UNEXPECTED: %s%n", e.getMessage());
            return "UNEXPECTED:" + e.getMessage();
        }
    }

    // -----------------------------------------------------------------------
    // Test scenarios
    // -----------------------------------------------------------------------

    static void scenario(String name, ApiResponse powerOff, ApiResponse delete) {
        System.out.printf("%n=== %s ===%n", name);

        System.out.println("  percona.3 (IOException only):");
        String buggyResult = terminateBuggy(42, powerOff, delete);

        System.out.println("  percona.4 (catch Exception):");
        String fixedResult = terminateFixed(42, powerOff, delete);

        System.out.printf("  Result: buggy=%s  fixed=%s%n", buggyResult, fixedResult);
    }

    public static void main(String[] args) {
        System.out.println("Hetzner destroyServer() Exception Handling Demo");
        System.out.println("===============================================");
        System.out.println();
        System.out.println("percona.3: destroyServer catches IOException only");
        System.out.println("percona.4: destroyServer catches Exception (all)");
        System.out.println();
        System.out.println("assertValidResponse() throws:");
        System.out.println("  - IOException for network errors");
        System.out.println("  - IllegalStateException for HTTP 429/412 (UNCHECKED)");

        // Normal operation
        scenario("Happy path (200 OK)",
            ApiResponse.OK_200, ApiResponse.OK_200);

        // Network error (IOException) - both versions handle this
        scenario("Network error on powerOff (IOException)",
            ApiResponse.NETWORK_ERROR, ApiResponse.OK_200);

        // Rate limited on powerOff - the gap!
        scenario("Rate limited on powerOff (HTTP 429 -> IllegalStateException)",
            ApiResponse.RATE_LIMITED_429, ApiResponse.OK_200);

        // Rate limited on delete (powerOff succeeded)
        scenario("Rate limited on delete (HTTP 429 -> IllegalStateException)",
            ApiResponse.OK_200, ApiResponse.RATE_LIMITED_429);

        // Precondition failed (HTTP 412)
        scenario("Precondition failed on powerOff (HTTP 412 -> IllegalStateException)",
            ApiResponse.PRECONDITION_FAILED_412, ApiResponse.OK_200);

        System.out.println("\n===============================================");
        System.out.println("SUMMARY");
        System.out.println("===============================================");
        System.out.println("percona.3: IllegalStateException escapes destroyServer(),");
        System.out.println("           caught by _terminate(). Server details lost from");
        System.out.println("           error context. CRW survives but logging is poor.");
        System.out.println();
        System.out.println("percona.4: ALL exceptions caught in destroyServer() with");
        System.out.println("           server ID and name in the log message. Defers to");
        System.out.println("           OrphanedNodesCleaner for retry. Clean stack.");
    }
}
