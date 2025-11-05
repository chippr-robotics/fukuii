# ADR-010: Apache HttpClient Transport for JupnP UPnP Port Forwarding

**Status**: Accepted

**Date**: November 2025

**Context**: Issue #308, PR #309

## Context

The Fukuii node was failing to start in certain environments due to a `URLStreamHandlerFactory` initialization error in the JupnP library:

```
ERROR [org.jupnp.transport.Router] - Unable to initialize network router: 
org.jupnp.transport.spi.InitializationException: Failed to set modified 
URLStreamHandlerFactory in this environment. Can't use bundled default 
client based on HTTPURLConnection, see manual.
```

### Background

**JupnP and UPnP Port Forwarding:**
- JupnP is used to automatically configure router port forwarding via UPnP (Universal Plug and Play)
- Enables peer-to-peer connectivity without manual router configuration
- Optional feature controlled by `Config.Network.automaticPortForwarding` setting

**The URLStreamHandlerFactory Problem:**
- JupnP's default HTTP transport (`HttpURLConnection`-based) requires setting a global `URLStreamHandlerFactory`
- The `URLStreamHandlerFactory` can only be set **once per JVM**
- If another library has already set it, or security policies prevent it, JupnP initialization fails
- The failure was fatal, preventing the entire node from starting

**When This Occurs:**
- When running in containers with security restrictions
- When other libraries have already claimed the `URLStreamHandlerFactory`
- In certain JVM environments or application servers
- With certain Java security managers enabled

**Impact:**
- Node fails to start completely
- Cannot sync blockchain or connect to peers
- UPnP is optional, but its failure should not prevent node operation

## Decision

We implemented a **custom Apache HttpClient-based transport** for JupnP that:

1. **Replaces the default `HttpURLConnection`-based transport** with Apache HttpComponents Client 5
2. **Eliminates the `URLStreamHandlerFactory` requirement** entirely
3. **Provides graceful degradation** if UPnP initialization still fails for other reasons
4. **Maintains full UPnP functionality** while being more robust

### Implementation

**New Dependency:**
```scala
"org.apache.httpcomponents.client5" % "httpclient5" % "5.3.1"
```

**New Component:** `ApacheHttpClientStreamClient`
- Implements JupnP's `StreamClient` interface
- Uses Apache HttpClient 5 for all HTTP operations
- Configures timeouts from `StreamClientConfiguration`
- Properly handles response charset encoding
- Includes error handling and logging

**Updated Component:** `PortForwarder`
- Replaced `JDKTransportConfiguration` with `ApacheHttpClientStreamClient`
- Added try-catch with graceful degradation
- Logs warnings if UPnP fails, but allows node to continue

## Rationale

### Why Apache HttpClient?

1. **No URLStreamHandlerFactory Required**
   - Apache HttpClient manages HTTP connections without JVM-global state
   - Works in restricted environments where factory cannot be set

2. **Mature, Well-Maintained Library**
   - Apache HttpComponents is industry-standard
   - Actively maintained with security updates
   - Extensive documentation and community support

3. **Modern Features**
   - HTTP/2 support (not needed now, but future-proof)
   - Better connection pooling and timeout management
   - Improved performance over `HttpURLConnection`

4. **Minimal Dependencies**
   - Single well-scoped dependency
   - No transitive dependency conflicts in our stack

### Why Not Alternative Solutions?

**Option 1: Just Catch and Ignore the Error**
- **Rejected**: UPnP would never work, even in environments where it could
- Loses functionality rather than fixing the root cause

**Option 2: Use Different UPnP Library**
- **Rejected**: JupnP is well-established and maintained
- Switching libraries is more risky than fixing the transport layer
- JupnP's architecture allows custom transports, which is the right extension point

**Option 3: System Property Workaround**
- **Rejected**: Undocumented, fragile, may not work in all cases
- Doesn't actually solve the problem, just tries to bypass it

**Option 4: Make UPnP Optional/Disable by Default**
- **Partially Implemented**: We added graceful degradation
- But we want UPnP to work when possible, not disable it entirely

## Consequences

### Positive

1. **Node Starts Successfully**
   - Even in restricted environments, node initialization completes
   - UPnP failure no longer blocks core functionality

2. **UPnP Works in More Environments**
   - Eliminates URLStreamHandlerFactory conflicts
   - Broader compatibility with different deployment scenarios

3. **Better Error Handling**
   - Graceful degradation with informative logging
   - Users know why UPnP failed and can take action if needed

4. **Modern HTTP Client**
   - Better performance and connection management
   - Future-proof with HTTP/2 support
   - Well-maintained dependency with security updates

5. **Minimal Code Changes**
   - Surgical fix targeting the specific problem
   - No changes to UPnP logic or port mapping functionality
   - Self-contained new module

### Negative

1. **Additional Dependency**
   - Adds `httpclient5` (~1.5MB) to the dependency tree
   - Minimal impact, but increases artifact size slightly

2. **Maintenance Burden**
   - Custom implementation requires maintenance
   - Must track Apache HttpClient API changes
   - However, the API is stable and changes infrequently

3. **Testing Complexity**
   - UPnP testing requires specific network environment
   - Cannot easily test in CI/CD without UPnP-enabled router
   - Must rely on manual testing and user feedback

4. **Implementation Complexity**
   - ~200 lines of custom transport code
   - More complex than using default transport
   - However, well-documented and straightforward

### Mitigations

1. **Dependency Size**: 1.5MB is negligible for a full node implementation
2. **Maintenance**: Apache HttpClient has stable API, updates are rare
3. **Testing**: Implementation follows JupnP patterns, code review ensures correctness
4. **Complexity**: Code is well-commented and follows standard patterns

## Implementation Details

**Key Components:**

1. **`ApacheHttpClientStreamClient`**
   - Extends `AbstractStreamClient[StreamClientConfiguration, HttpCallable]`
   - Configures HttpClient with timeouts from configuration
   - Handles GET and POST requests for UPnP SOAP messages

2. **`HttpCallable`**
   - Implements `Callable[StreamResponseMessage]`
   - Executes HTTP requests and converts responses to JupnP format
   - Handles aborts and errors gracefully

3. **Request/Response Handling**
   - Preserves all headers from JupnP requests
   - Extracts charset from Content-Type header
   - Properly handles HTTP status codes and error responses

4. **Error Handling**
   - Try-catch in `PortForwarder.startForwarding()`
   - Logs warnings for `InitializationException` and other errors
   - Returns `NoOpUpnpService` to allow clean shutdown

**Configuration:**
- Timeouts: Configured from `StreamClientConfiguration.getTimeoutSeconds()`
- Connection timeout: Matches configured timeout
- Response timeout: Matches configured timeout
- User-Agent: "Fukuii/{version} UPnP/1.1"

## Alternatives Considered

See "Why Not Alternative Solutions?" section above.

## Testing

**Compilation**: ✅ Successfully compiles with no errors or warnings

**Code Review**: ✅ Addressed feedback on:
- HttpClient timeout configuration
- Code duplication reduction
- Charset encoding handling

**Security Analysis**: ✅ CodeQL analysis passed with no vulnerabilities

**Manual Testing**: Requires UPnP-enabled router environment
- Node should start successfully in restricted environments
- UPnP port forwarding should work when router supports it
- Graceful degradation when UPnP unavailable

## Future Considerations

1. **Monitor Apache HttpClient Updates**
   - Track security advisories for httpclient5
   - Update dependency regularly with patch releases

2. **Consider HTTP/2**
   - If UPnP protocol adds HTTP/2 support, we're ready
   - Apache HttpClient 5 supports HTTP/2 natively

3. **Enhanced Error Reporting**
   - Could add more detailed diagnostics for UPnP failures
   - Help users understand why UPnP isn't working

4. **Alternative Port Forwarding Methods**
   - Consider NAT-PMP/PCP as fallback if UPnP fails
   - Could use similar Apache HttpClient approach

## References

- [Issue #308: URLSTREAMHANDLERFACTORY failure](https://github.com/chippr-robotics/fukuii/issues/308)
- [PR #309: Fix JupnP URLStreamHandlerFactory conflict](https://github.com/chippr-robotics/fukuii/pull/309)
- [JupnP Documentation](https://github.com/jupnp/jupnp)
- [Apache HttpComponents Client](https://hc.apache.org/httpcomponents-client-5.3.x/)
- [UPnP Device Architecture](https://openconnectivity.org/developer/specifications/upnp-resources/upnp/)

## Related ADRs

- ADR-001: Migration to Scala 3 and JDK 21 (dependency compatibility)

## Review and Update

This ADR should be reviewed when:
- Apache HttpClient releases a major version (6.x)
- JupnP library is upgraded to a new major version
- UPnP port forwarding issues are reported
- Alternative UPnP libraries emerge with better Java compatibility
