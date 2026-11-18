package com.chipprbots.ethereum.tls

import java.nio.file.Files
import java.nio.file.Path

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory

/** Test helpers: temp directories and the `fukuii` configuration sections the TLS code reads. */
object TlsTestSupport:

  def tempDir(prefix: String = "tls-test"): Path = Files.createTempDirectory(prefix)

  def deleteTree(dir: Path): Unit =
    if Files.exists(dir) then
      val stream = Files.walk(dir)
      try
        stream.sorted(java.util.Comparator.reverseOrder()).forEach { p =>
          Files.deleteIfExists(p); ()
        }
      finally stream.close()

  private def q(p: Path): String = "\"" + p.toString.replace("\\", "/") + "\""

  def storeHocon(f: TestPki#Files_, storeType: String = "pkcs12"): String =
    s"""{ path = ${q(f.store)}, type = "$storeType", password-file = ${q(f.passwordFile)} }"""

  /** The `fukuii` section with an HTTP listener (plain unless `http` says otherwise) and the given profiles. */
  def config(profiles: String = "", http: String = ""): Config =
    ConfigFactory.parseString(s"""
      network.rpc.http { mode = "http", enabled = true, interface = "127.0.0.1", port = 0, $http }
      tls.profiles { $profiles }
    """)

  def profile(
      name: String,
      keystore: TestPki#Files_,
      truststore: Option[TestPki#Files_] = None,
      extra: String = ""
  ): String =
    s"""$name { keystore = ${storeHocon(keystore)}
       |  ${truststore.map(t => s"truststore = ${storeHocon(t)}").getOrElse("")}
       |  $extra }""".stripMargin
