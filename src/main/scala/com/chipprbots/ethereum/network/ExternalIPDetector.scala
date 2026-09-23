package com.chipprbots.ethereum.network

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

import scala.jdk.CollectionConverters.*
import scala.util.Try
import scala.util.Using

import org.jupnp.UpnpServiceImpl
import org.jupnp.model.action.ActionInvocation
import org.jupnp.model.message.UpnpResponse
import org.jupnp.model.meta.LocalDevice
import org.jupnp.model.meta.RemoteDevice
import org.jupnp.registry.Registry
import org.jupnp.registry.RegistryListener
import org.jupnp.support.igd.callback.GetExternalIP

import com.chipprbots.ethereum.utils.Logger

/** Detects this node's externally reachable IPv4 address via a best-effort cascade of independent probes, validating
  * every candidate before it is returned.
  *
  * Cascade order:
  *   1. UPnP IGD (GetExternalIP) — asks the local gateway router for its WAN address 2. STUN (RFC 5389 Binding Request)
  *      — fast UDP round trip against a public STUN server 3. HTTPS probe — a plaintext IPv4 literal returned by a
  *      public "what's my IP" endpoint 4. First non-loopback, non-link-local IPv4 address bound to a local network
  *      interface
  *
  * Every candidate — regardless of which step produced it — is validated with [[isPublicIPv4]] before being accepted;
  * private-use, loopback, link-local, CGNAT, documentation, benchmarking, multicast, and reserved ranges are all
  * rejected and the cascade continues to the next step. A rejected candidate is logged at DEBUG and never propagates
  * further.
  *
  * Called once at startup when no explicit advertised address is configured (see
  * `network.server-address.advertised-address`).
  */
object ExternalIPDetector extends Logger:

  private val UpnpTimeoutMs = 3000

  private val StunServers: List[(String, Int)] = List(
    "stun.l.google.com" -> 19302,
    "stun1.l.google.com" -> 19302,
    "stun.cloudflare.com" -> 3478
  )
  private val StunTimeoutMs = 2000

  // Public HTTPS "what is my IP" endpoints, queried in order; the first plaintext IPv4 response wins.
  private val HttpProbeUrls: List[String] = List(
    "https://icanhazip.com",
    "https://checkip.amazonaws.com",
    "https://api4.ipify.org",
    "https://4.ident.me"
  )
  private val HttpTimeoutMs = 2000
  private val HttpMaxBodyBytes = 64

  /** Returns the best available externally reachable address, or None if every step failed or produced only non-public
    * candidates.
    */
  def detect(): Option[InetAddress] =
    tryUpnp().orElse(tryStun()).orElse(tryHttp()).orElse(tryLocalInterface())

  /** True only for an IPv4 address outside every reserved/private/documentation/multicast range. Never true for an IPv6
    * address. Checked as explicit prefix comparisons on the 32-bit value, not via `InetAddress`'s own
    * `isSiteLocalAddress`/`isLoopbackAddress` helpers alone — those don't cover CGNAT, documentation, or benchmarking
    * ranges.
    */
  def isPublicIPv4(addr: InetAddress): Boolean = addr match
    case v4: Inet4Address =>
      val bytes = v4.getAddress
      val value = ip4(bytes(0) & 0xff, bytes(1) & 0xff, bytes(2) & 0xff, bytes(3) & 0xff)
      !BlockedRanges.exists { case (base, prefixLen) => inRange(value, base, prefixLen) }
    case _ => false

  /** Parses a strict dotted-decimal IPv4 literal: exactly four decimal octets 0-255, no leading zeros (other than the
    * literal digit "0"), nothing else. Never resolves a hostname — unlike `InetAddress.getByName`, this never performs
    * a DNS lookup or blocks on I/O.
    */
  def parseIpv4Literal(raw: String): Option[Inet4Address] =
    val trimmed = raw.trim
    if trimmed.isEmpty then None
    else
      val parts = trimmed.split("\\.", -1)
      if parts.length != 4 then None
      else
        val octets = parts.toList.map(parseOctet)
        if octets.forall(_.isDefined) then
          val bytes = octets.map(_.get.toByte).toArray
          Try(InetAddress.getByAddress(bytes)).toOption.collect { case v4: Inet4Address => v4 }
        else None

  private def parseOctet(s: String): Option[Int] =
    if s.isEmpty || s.length > 3 then None
    else if !s.forall(c => c >= '0' && c <= '9') then None
    else if s.length > 1 && s.charAt(0) == '0' then None // reject leading zeros, except the literal "0"
    else
      val v = s.toInt
      if v >= 0 && v <= 255 then Some(v) else None

  private def ip4(a: Int, b: Int, c: Int, d: Int): Int =
    ((a & 0xff) << 24) | ((b & 0xff) << 16) | ((c & 0xff) << 8) | (d & 0xff)

  private def prefixMask(prefixLen: Int): Int =
    if prefixLen <= 0 then 0 else -1 << (32 - prefixLen)

  private def inRange(value: Int, base: Int, prefixLen: Int): Boolean =
    val mask = prefixMask(prefixLen)
    (value & mask) == (base & mask)

  // RFC 1918 / RFC 6598 / RFC 5735 / RFC 2544 reserved, private-use, documentation, benchmarking,
  // multicast, and "reserved for future use" IPv4 ranges. A detected address inside any of these is not
  // externally reachable and must never be advertised to peers.
  private val BlockedRanges: List[(Int, Int)] = List(
    ip4(0, 0, 0, 0) -> 8, // 0.0.0.0/8 — "this network"
    ip4(10, 0, 0, 0) -> 8, // 10.0.0.0/8 — private-use
    ip4(100, 64, 0, 0) -> 10, // 100.64.0.0/10 — shared address space (CGNAT)
    ip4(127, 0, 0, 0) -> 8, // 127.0.0.0/8 — loopback
    ip4(169, 254, 0, 0) -> 16, // 169.254.0.0/16 — link-local
    ip4(172, 16, 0, 0) -> 12, // 172.16.0.0/12 — private-use
    ip4(192, 0, 0, 0) -> 24, // 192.0.0.0/24 — IETF protocol assignments
    ip4(192, 0, 2, 0) -> 24, // 192.0.2.0/24 — documentation (TEST-NET-1)
    ip4(192, 168, 0, 0) -> 16, // 192.168.0.0/16 — private-use
    ip4(198, 18, 0, 0) -> 15, // 198.18.0.0/15 — benchmarking
    ip4(198, 51, 100, 0) -> 24, // 198.51.100.0/24 — documentation (TEST-NET-2)
    ip4(203, 0, 113, 0) -> 24, // 203.0.113.0/24 — documentation (TEST-NET-3)
    ip4(224, 0, 0, 0) -> 4, // 224.0.0.0/4 — multicast
    ip4(240, 0, 0, 0) -> 4 // 240.0.0.0/4 — reserved for future use
  )

  /** Accepts `addr` only if it passes [[isPublicIPv4]]; otherwise logs the rejection at DEBUG and returns None so the
    * cascade in [[detect]] moves on to the next step.
    */
  private def validate(source: String, addr: InetAddress): Option[InetAddress] =
    if isPublicIPv4(addr) then Some(addr)
    else
      log.debug("Rejected external-ip candidate: source={} address={}", source, addr.getHostAddress)
      None

  // Step 1: UPnP IGD GetExternalIP — asks the gateway for its WAN address.
  // Creates a short-lived UpnpServiceImpl (client-only, no stream server) and shuts it down after
  // collecting the result or timing out. Silent None on VPS / firewalled environments.
  private[network] def tryUpnp(): Option[InetAddress] = Try {
    val ipFuture = new CompletableFuture[String]()
    val upnpSvc = new UpnpServiceImpl(new ClientOnlyUpnpServiceConfiguration())
    try
      upnpSvc.startup()
      upnpSvc
        .getRegistry()
        .addListener(new RegistryListener():
          // Walk a device tree looking for a WANIPConnection or WANPPPConnection service and
          // execute GetExternalIP as soon as one is found.
          private def walkDevice(d: RemoteDevice): Unit =
            for svc <- d.getServices() do
              if svc.getServiceType.getType == "WANIPConnection" ||
                svc.getServiceType.getType == "WANPPPConnection"
              then
                upnpSvc
                  .getControlPoint()
                  .execute(new GetExternalIP(svc):
                    protected def success(ip: String): Unit =
                      ipFuture.complete(ip)

                    @SuppressWarnings(Array("rawtypes"))
                    def failure(inv: ActionInvocation[?], resp: UpnpResponse, msg: String): Unit = ()
                    // Don't completeExceptionally here — on multi-IGD networks a later device may
                    // succeed. Total UPnP failure is handled by the ipFuture.get() timeout below.
                  )
            for sub <- d.getEmbeddedDevices() do walkDevice(sub)

          def remoteDeviceAdded(r: Registry, d: RemoteDevice): Unit = walkDevice(d)
          def remoteDeviceDiscoveryStarted(r: Registry, d: RemoteDevice): Unit = ()
          def remoteDeviceDiscoveryFailed(r: Registry, d: RemoteDevice, e: Exception): Unit = ()
          def remoteDeviceUpdated(r: Registry, d: RemoteDevice): Unit = ()
          def remoteDeviceRemoved(r: Registry, d: RemoteDevice): Unit = ()
          def localDeviceAdded(r: Registry, d: LocalDevice): Unit = ()
          def localDeviceRemoved(r: Registry, d: LocalDevice): Unit = ()
          def beforeShutdown(r: Registry): Unit = ()
          def afterShutdown(): Unit = ()
        )
      upnpSvc.getControlPoint().search()
      Try(ipFuture.get(UpnpTimeoutMs, TimeUnit.MILLISECONDS)).toOption
    finally
      try upnpSvc.shutdown()
      catch case _: Throwable => ()
  }.toOption.flatten
    .flatMap(parseIpv4Literal) // never InetAddress.getByName — the gateway-supplied string is untrusted input
    .flatMap(addr => validate("upnp", addr))

  // Step 2: RFC 5389 STUN Binding Request — fast UDP, typically <100ms on internet-connected hosts.
  // `servers` is injectable so tests can point the cascade at a fake STUN server on loopback.
  private[network] def tryStun(servers: List[(String, Int)] = StunServers): Option[InetAddress] =
    servers.iterator
      .flatMap { case (host, port) => stunProbe(host, port).toOption }
      .flatMap(addr => validate("stun", addr))
      .nextOption()

  private def stunProbe(host: String, port: Int): Try[InetAddress] = Try {
    val serverAddress = InetAddress.getByName(host)
    Using.resource(new DatagramSocket()) { socket =>
      socket.setSoTimeout(StunTimeoutMs)
      // Connect the socket to the STUN server so the kernel drops any UDP datagram arriving from a
      // different source address — an unconnected socket would happily hand a spoofed reply to receive().
      socket.connect(serverAddress, port)
      // RFC 5389 Binding Request: 20-byte header, no attributes.
      // Layout: type(2) | length(2) | magic(4) | txId(12)
      val req = new Array[Byte](20)
      val hdr = ByteBuffer.wrap(req)
      hdr.putShort(0x0001.toShort) // Binding Request
      hdr.putShort(0x0000.toShort) // Message Length = 0 (no body attributes)
      hdr.putInt(0x2112a442) // Magic Cookie (required by RFC 5389)
      val txId = new Array[Byte](12)
      new java.security.SecureRandom().nextBytes(txId) // RFC 5389 §6: random 96-bit
      hdr.put(txId)
      socket.send(new DatagramPacket(req, 20, serverAddress, port))
      val buf = new Array[Byte](1024)
      val recv = new DatagramPacket(buf, buf.length)
      socket.receive(recv)
      parseXorMappedAddress(buf, recv.getLength, txId)
    }
  }

  private[network] def parseXorMappedAddress(buf: Array[Byte], len: Int, txId: Array[Byte]): InetAddress =
    if len < 20 then throw new IllegalStateException("STUN response truncated: header requires 20 bytes")
    val resp = ByteBuffer.wrap(buf, 0, len)
    val msgType = resp.getShort() & 0xffff
    if msgType != 0x0101 then
      throw new IllegalStateException(s"Expected Binding Response (0x0101), got 0x${msgType.toHexString}")
    val msgLen = resp.getShort() & 0xffff
    val bodyEnd = 20 + msgLen
    if bodyEnd > len then
      throw new IllegalStateException("STUN response: declared message length exceeds received bytes")
    resp.position(4) // skip type(2) + length(2), land at magic cookie
    if (resp.getInt() & 0xffffffffL) != 0x2112a442L then
      throw new IllegalStateException("STUN response: unexpected magic cookie")
    val echoed = new Array[Byte](12)
    resp.get(echoed)
    if !java.util.Arrays.equals(echoed, txId) then
      throw new IllegalStateException("STUN response transaction ID mismatch — possible spoofing or server reuse")
    // position is now at 20 — start of attribute section
    var result: Option[InetAddress] = None
    while resp.position() < bodyEnd && result.isEmpty do
      if bodyEnd - resp.position() < 4 then throw new IllegalStateException("STUN response: truncated attribute header")
      val attrType = resp.getShort() & 0xffff
      val attrLen = resp.getShort() & 0xffff
      val attrStart = resp.position() // start of attribute VALUE (after type+length headers)
      if attrStart + attrLen > bodyEnd then
        throw new IllegalStateException("STUN response: attribute length exceeds message bounds")
      if attrType == 0x0020 then // XOR-MAPPED-ADDRESS
        if attrLen < 8 then throw new IllegalStateException("STUN response: XOR-MAPPED-ADDRESS attribute too short")
        resp.get() // reserved byte
        val family = resp.get() & 0xff
        if family == 0x01 then // IPv4 only — an IPv6 (0x02) family is skipped, never accepted
          resp.getShort() // xor-port (unused — we only need the IP)
          val xorAddr = resp.getInt() ^ 0x2112a442
          result = Some(InetAddress.getByAddress(ByteBuffer.allocate(4).putInt(xorAddr).array()))
      // Advance past the full padded attribute value (4-byte alignment)
      val paddedLen = (attrLen + 3) & ~3
      if attrStart + paddedLen > len then
        throw new IllegalStateException("STUN response: attribute padding exceeds received bytes")
      resp.position(attrStart + paddedLen)
    result.getOrElse(
      throw new IllegalStateException("STUN response contained no XOR-MAPPED-ADDRESS for IPv4")
    )

  // Step 3: HTTPS probe — reads at most HttpMaxBodyBytes of the response body and parses the first line
  // as a strict IPv4 literal. `urls` is injectable for tests.
  private[network] def tryHttp(urls: List[String] = HttpProbeUrls): Option[InetAddress] =
    urls.iterator
      .flatMap(url => httpProbe(url).toOption)
      .flatMap(addr => validate("http", addr))
      .nextOption()

  private def httpProbe(url: String): Try[Inet4Address] = Try {
    val conn = new java.net.URI(url).toURL().openConnection()
    conn.setConnectTimeout(HttpTimeoutMs)
    conn.setReadTimeout(HttpTimeoutMs)
    Using.resource(conn.getInputStream()) { in =>
      val buf = new Array[Byte](HttpMaxBodyBytes)
      val n = in.read(buf)
      if n <= 0 then throw new IllegalStateException("Empty HTTP probe response body")
      val text = new String(buf, 0, n, StandardCharsets.UTF_8)
      val firstLine = text.linesIterator.nextOption().getOrElse(text)
      parseIpv4Literal(firstLine).getOrElse(
        throw new IllegalStateException("HTTP probe response body is not an IPv4 literal")
      )
    }
  }

  // Step 4: first non-loopback, non-link-local IPv4 interface address.
  private[network] def tryLocalInterface(): Option[InetAddress] =
    Option(NetworkInterface.getNetworkInterfaces)
      .map(_.asScala.flatMap(_.getInetAddresses.asScala))
      .getOrElse(Iterator.empty)
      .collectFirst {
        case addr: Inet4Address if !addr.isLoopbackAddress && !addr.isLinkLocalAddress => addr
      }
      .flatMap(addr => validate("local-interface", addr))
