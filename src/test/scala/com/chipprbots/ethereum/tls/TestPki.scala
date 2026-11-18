package com.chipprbots.ethereum.tls

import java.io.OutputStream
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.time.Duration
import java.time.Instant
import java.util.Date

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

/** A certificate and its key, for a test. */
final case class PkiIdentity(key: KeyPair, cert: X509Certificate, chain: Array[X509Certificate])

/** A throwaway PKI for tests (spec 030 research D-12): a CA, leaves with SAN and CN variants, expired and not-yet-valid
  * leaves, and PKCS#12 and JKS stores with password files. Everything is written under `dir`, a per-test temp
  * directory; no key material is ever committed.
  */
final class TestPki(val dir: Path):

  private val provider = new BouncyCastleProvider()
  private val converter = new JcaX509CertificateConverter().setProvider(provider)
  private var serial = 1L

  private def newKeys(): KeyPair =
    val gen = KeyPairGenerator.getInstance("EC")
    gen.initialize(256)
    gen.generateKeyPair()

  private def nextSerial(): BigInteger =
    serial += 1
    BigInteger.valueOf(serial)

  private def issue(
      subject: String,
      issuer: Option[PkiIdentity],
      notBefore: Instant,
      notAfter: Instant,
      isCa: Boolean,
      sanDns: Seq[String],
      sanUri: Seq[String]
  ): PkiIdentity =
    val keys = newKeys()
    val subjectName = new X500Name(subject)
    val signer = issuer.map(_.key).getOrElse(keys)
    val issuerName = issuer.map(i => new X500Name(i.cert.getSubjectX500Principal.getName)).getOrElse(subjectName)
    val builder = new JcaX509v3CertificateBuilder(
      issuerName,
      nextSerial(),
      Date.from(notBefore),
      Date.from(notAfter),
      subjectName,
      keys.getPublic
    )
    builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(isCa))
    builder.addExtension(
      Extension.keyUsage,
      true,
      new KeyUsage(if isCa then KeyUsage.keyCertSign | KeyUsage.cRLSign else KeyUsage.digitalSignature)
    )
    val names = sanDns.map(new GeneralName(GeneralName.dNSName, _)) ++
      sanUri.map(new GeneralName(GeneralName.uniformResourceIdentifier, _))
    if names.nonEmpty then
      builder.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(names.toArray))
    val cert =
      converter.getCertificate(builder.build(new JcaContentSignerBuilder("SHA256withECDSA").build(signer.getPrivate)))
    PkiIdentity(keys, cert, issuer.map(i => cert +: i.chain).getOrElse(Array(cert)))

  private def now: Instant = Instant.now()

  val ca: PkiIdentity =
    issue("CN=Test CA", None, now.minus(Duration.ofDays(1)), now.plus(Duration.ofDays(30)), true, Nil, Nil)

  /** A second, unrelated CA: what a certificate from "the other CA" is signed by. */
  def otherCa(): PkiIdentity =
    issue("CN=Other CA", None, now.minus(Duration.ofDays(1)), now.plus(Duration.ofDays(30)), true, Nil, Nil)

  def leaf(
      cn: String = "localhost",
      sanDns: Seq[String] = Seq("localhost"),
      sanUri: Seq[String] = Nil,
      notBefore: Instant = now.minus(Duration.ofDays(1)),
      notAfter: Instant = now.plus(Duration.ofDays(30)),
      signedBy: PkiIdentity = ca
  ): PkiIdentity = issue(s"CN=$cn", Some(signedBy), notBefore, notAfter, false, sanDns, sanUri)

  def expiredLeaf(cn: String = "localhost"): PkiIdentity =
    leaf(cn, notBefore = now.minus(Duration.ofDays(10)), notAfter = now.minus(Duration.ofDays(1)))

  def notYetValidLeaf(cn: String = "localhost"): PkiIdentity =
    leaf(cn, notBefore = now.plus(Duration.ofDays(1)), notAfter = now.plus(Duration.ofDays(10)))

  /** The files of one store: the store and its password file. */
  final case class Files_(store: Path, passwordFile: Path, password: String):
    def ref(storeType: String): StoreRef = StoreRef(store, storeType, passwordFile)

  private def write(file: Path, f: OutputStream => Unit): Unit =
    val out = Files.newOutputStream(file)
    try f(out)
    finally out.close()

  /** A keystore holding the given leaves (alias = the name), its password file with or without a trailing newline. */
  def keystore(
      name: String,
      entries: Seq[(String, PkiIdentity)],
      password: String = "keystore-pw",
      storeType: String = "pkcs12",
      trailingNewline: Boolean = false
  ): Files_ =
    val ks = KeyStore.getInstance(storeType)
    ks.load(null, null)
    entries.foreach((alias, id) =>
      ks.setKeyEntry(alias, id.key.getPrivate, password.toCharArray, id.chain.toArray[java.security.cert.Certificate])
    )
    val file = dir.resolve(s"$name.${if storeType == "jks" then "jks" else "p12"}")
    write(file, out => ks.store(out, password.toCharArray))
    Files_(file, passwordFile(name, password, trailingNewline), password)

  /** A truststore holding the given certificates as trusted entries. */
  def truststore(
      name: String,
      certs: Seq[X509Certificate],
      password: String = "truststore-pw",
      storeType: String = "pkcs12"
  ): Files_ =
    val ks = KeyStore.getInstance(storeType)
    ks.load(null, null)
    certs.zipWithIndex.foreach((c, i) => ks.setCertificateEntry(s"ca$i", c))
    val file = dir.resolve(s"$name.${if storeType == "jks" then "jks" else "p12"}")
    write(file, out => ks.store(out, password.toCharArray))
    Files_(file, passwordFile(name, password, false), password)

  private def passwordFile(name: String, password: String, trailingNewline: Boolean): Path =
    val file = dir.resolve(s"$name.password")
    Files.write(file, (password + (if trailingNewline then "\n" else "")).getBytes(StandardCharsets.UTF_8))
    file

  /** A client-side trust manager's SSL context: trusts `ca`, presents no certificate. */
  def clientContext(trusted: Seq[X509Certificate] = Seq(ca.cert)): javax.net.ssl.SSLContext =
    val ks = KeyStore.getInstance("pkcs12")
    ks.load(null, null)
    trusted.zipWithIndex.foreach((c, i) => ks.setCertificateEntry(s"t$i", c))
    val tmf = javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm)
    tmf.init(ks)
    val ctx = javax.net.ssl.SSLContext.getInstance("TLS")
    ctx.init(null, tmf.getTrustManagers, new java.security.SecureRandom())
    ctx
