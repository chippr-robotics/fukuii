package com.chipprbots.ethereum.network

import java.net.{DatagramPacket, DatagramSocket, Inet4Address, InetAddress, NetworkInterface}
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.{CompletableFuture, TimeUnit}

import scala.jdk.CollectionConverters._
import scala.util.{Try, Using}

import org.jupnp.UpnpServiceImpl
import org.jupnp.model.action.ActionInvocation
import org.jupnp.model.message.UpnpResponse
import org.jupnp.model.meta.{LocalDevice, RemoteDevice}
import org.jupnp.registry.{Registry, RegistryListener}
import org.jupnp.support.igd.callback.GetExternalIP

/** Detects the node's externally reachable IP address via a best-effort cascade:
  *   1. UPnP IGD (GetExternalIP) — queries the local gateway router; works on most home NATs 2. STUN (RFC 5389 Binding
  *      Request) — fast UDP, works through most NATs 3. HTTP probe — falls back if STUN is blocked 4. First
  *      non-loopback IPv4 interface — last resort for air-gapped / firewalled hosts
  *
  * Mirrors the strategy used by core-geth (--nat=any: UPnP → STUN → HTTP) and Besu. Called once at startup when no
  * explicit advertised-address is configured.
  */
object ExternalIPDetector {

  private val UpnpTimeoutMs = 3000

  private val StunServers: List[(String, Int)] = List(
    "stun.l.google.com" -> 19302,
    "stun1.l.google.com" -> 19302,
    "stun.cloudflare.com" -> 3478
  )
  private val StunTimeoutMs = 2000

  // Same canonical set as Besu HttpProbeIpDetector and Nethermind IPResolver (HTTPS only).
  private val HttpProbeUrls: List[String] = List(
    "https://icanhazip.com",
    "https://checkip.amazonaws.com",
    "https://api4.ipify.org",
    "https://4.ident.me"
  )
  private val HttpTimeoutMs = 2000

  /** Returns the best available externally reachable address, or None if all methods fail. */
  def detect(): Option[InetAddress] =
    tryUpnp().orElse(tryStun()).orElse(tryHttp()).orElse(tryLocalInterface())

  // Step 1: UPnP IGD GetExternalIP — asks the gateway for its WAN address.
  // Creates a short-lived UpnpServiceImpl (client-only, no stream server) and shuts it down after
  // collecting the result or timing out. Silent None on VPS / firewalled environments.
  private def tryUpnp(): Option[InetAddress] = Try {
    val ipFuture = new CompletableFuture[String]()
    val upnpSvc = new UpnpServiceImpl(new ClientOnlyUpnpServiceConfiguration())
    try {
      upnpSvc.startup()
      upnpSvc
        .getRegistry()
        .addListener(new RegistryListener() {
          // Walk a device tree looking for a WANIPConnection or WANPPPConnection service and
          // execute GetExternalIP as soon as one is found.
          private def walkDevice(d: RemoteDevice): Unit = {
            for (svc <- d.getServices())
              if (
                svc.getServiceType.getType == "WANIPConnection" ||
                svc.getServiceType.getType == "WANPPPConnection"
              )
                upnpSvc
                  .getControlPoint()
                  .execute(new GetExternalIP(svc) {
                    protected def success(ip: String): Unit =
                      ipFuture.complete(ip)

                    @SuppressWarnings(Array("rawtypes"))
                    def failure(inv: ActionInvocation[?], resp: UpnpResponse, msg: String): Unit =
                      if (!ipFuture.isDone) ipFuture.completeExceptionally(new Exception(msg))
                  })
            for (sub <- d.getEmbeddedDevices())
              walkDevice(sub)
          }

          def remoteDeviceAdded(r: Registry, d: RemoteDevice): Unit = walkDevice(d)
          def remoteDeviceDiscoveryStarted(r: Registry, d: RemoteDevice): Unit = ()
          def remoteDeviceDiscoveryFailed(r: Registry, d: RemoteDevice, e: Exception): Unit = ()
          def remoteDeviceUpdated(r: Registry, d: RemoteDevice): Unit = ()
          def remoteDeviceRemoved(r: Registry, d: RemoteDevice): Unit = ()
          def localDeviceAdded(r: Registry, d: LocalDevice): Unit = ()
          def localDeviceRemoved(r: Registry, d: LocalDevice): Unit = ()
          def beforeShutdown(r: Registry): Unit = ()
          def afterShutdown(): Unit = ()
        })
      upnpSvc.getControlPoint().search()
      Try(ipFuture.get(UpnpTimeoutMs, TimeUnit.MILLISECONDS)).toOption
    } finally
      try upnpSvc.shutdown()
      catch { case _: Throwable => () }
  }.toOption.flatten.flatMap(ip => Try(InetAddress.getByName(ip)).toOption)

  // Step 2: RFC 5389 STUN Binding Request — fast UDP, typically <100ms on internet-connected hosts.
  private def tryStun(): Option[InetAddress] =
    StunServers.iterator
      .flatMap { case (host, port) =>
        stunProbe(host, port).toOption
      }
      .nextOption()

  private def stunProbe(host: String, port: Int): Try[InetAddress] = Try {
    Using.resource(new DatagramSocket()) { socket =>
      socket.setSoTimeout(StunTimeoutMs)
      // RFC 5389 Binding Request: 20-byte header, no attributes
      val req = new Array[Byte](20)
      val hdr = ByteBuffer.wrap(req)
      hdr.putShort(0x0001.toShort) // Binding Request
      hdr.putShort(0x0000.toShort) // Message Length = 0 (no body attributes)
      hdr.putInt(0x2112a442) // Magic Cookie (required by RFC 5389)
      // Transaction ID: 12 zero bytes (we don't validate it in the response)
      socket.send(new DatagramPacket(req, 20, InetAddress.getByName(host), port))
      val buf = new Array[Byte](1024)
      val recv = new DatagramPacket(buf, buf.length)
      socket.receive(recv)
      parseXorMappedAddress(buf, recv.getLength)
    }
  }

  private def parseXorMappedAddress(buf: Array[Byte], len: Int): InetAddress = {
    val resp = ByteBuffer.wrap(buf, 0, len)
    val msgType = resp.getShort() & 0xffff
    if (msgType != 0x0101)
      throw new IllegalStateException(s"Expected Binding Response (0x0101), got 0x${msgType.toHexString}")
    val msgLen = resp.getShort() & 0xffff
    resp.position(20) // skip magic cookie (4) + transaction ID (12), land at attribute section
    val bodyEnd = 20 + msgLen
    var result: Option[InetAddress] = None
    while (resp.position() < bodyEnd && result.isEmpty) {
      val attrType = resp.getShort() & 0xffff
      val attrLen = resp.getShort() & 0xffff
      val attrStart = resp.position() // start of attribute VALUE (after type+length headers)
      if (attrType == 0x0020) { // XOR-MAPPED-ADDRESS
        resp.get() // reserved byte
        val family = resp.get() & 0xff
        if (family == 0x01) { // IPv4
          resp.getShort() // xor-port (unused — we only need the IP)
          val xorAddr = resp.getInt() ^ 0x2112a442
          result = Some(InetAddress.getByAddress(ByteBuffer.allocate(4).putInt(xorAddr).array()))
        }
      }
      // Advance past the full padded attribute value (4-byte alignment)
      resp.position(attrStart + ((attrLen + 3) & ~3))
    }
    result.getOrElse(
      throw new IllegalStateException("STUN response contained no XOR-MAPPED-ADDRESS for IPv4")
    )
  }

  // Step 3: HTTPS probe — same approach as Besu HttpProbeIpDetector / Nethermind IPResolver.
  private def tryHttp(): Option[InetAddress] =
    HttpProbeUrls.iterator
      .flatMap { url =>
        Try {
          val conn = new java.net.URI(url).toURL().openConnection()
          conn.setConnectTimeout(HttpTimeoutMs)
          conn.setReadTimeout(HttpTimeoutMs)
          val reader =
            new java.io.BufferedReader(
              new java.io.InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8)
            )
          try {
            val line = reader.readLine()
            if (line == null || line.trim.isEmpty)
              throw new IllegalStateException(s"Empty response from $url")
            InetAddress.getByName(line.trim)
          } finally reader.close()
        }.toOption
      }
      .nextOption()

  // Step 4: first non-loopback, non-link-local IPv4 interface address (LAN IP).
  private def tryLocalInterface(): Option[InetAddress] =
    Option(NetworkInterface.getNetworkInterfaces)
      .map(_.asScala.flatMap(_.getInetAddresses.asScala))
      .getOrElse(Iterator.empty)
      .find { addr =>
        !addr.isLoopbackAddress &&
        !addr.isLinkLocalAddress &&
        addr.isInstanceOf[Inet4Address]
      }
}
