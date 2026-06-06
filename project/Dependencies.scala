import sbt._

object Dependencies {

  // Apache Pekko - Scala 3 compatible fork of Akka
  private val pekkoVersion = "1.6.0"
  private val pekkoHttpVersion = "1.3.0"

  val pekkoUtil: Seq[ModuleID] =
    Seq(
      "org.apache.pekko" %% "pekko-actor" % pekkoVersion
    )

  val pekko: Seq[ModuleID] =
    Seq(
      "org.apache.pekko" %% "pekko-actor" % pekkoVersion,
      "org.apache.pekko" %% "pekko-actor-typed" % pekkoVersion,
      "org.apache.pekko" %% "pekko-slf4j" % pekkoVersion,
      "org.apache.pekko" %% "pekko-testkit" % pekkoVersion % "it,test",
      "org.apache.pekko" %% "pekko-actor-testkit-typed" % pekkoVersion % "it,test",
      "org.apache.pekko" %% "pekko-stream" % pekkoVersion
    )

  val pekkoHttp: Seq[ModuleID] =
    Seq(
      "org.apache.pekko" %% "pekko-http" % pekkoHttpVersion,
      "org.apache.pekko" %% "pekko-http-cors" % pekkoHttpVersion,
      "org.apache.pekko" %% "pekko-http-testkit" % pekkoHttpVersion % "it,test",
      // Note: pekko-http-json4s not yet available, using custom JSON marshalling with json4s
      "org.json4s" %% "json4s-native" % "4.0.7"
    )

  val json4s = Seq("org.json4s" %% "json4s-native" % "4.0.7") // Updated for Scala 3 support

  val circe: Seq[ModuleID] = {
    val circeVersion = "0.14.15"

    Seq(
      "io.circe" %% "circe-core" % circeVersion,
      "io.circe" %% "circe-generic" % circeVersion,
      "io.circe" %% "circe-parser" % circeVersion
      // NOTE: circe-generic-extras is deprecated and not available for Scala 3
      // Functionality has been integrated into circe-generic in 0.14.x
      // See: https://github.com/circe/circe-generic-extras/issues/276
    )
  }

  // Sangria GraphQL (EIP-1767 execution-layer GraphQL endpoint)
  val sangria: Seq[ModuleID] = Seq(
    "org.sangria-graphql" %% "sangria" % "4.2.18",
    "org.sangria-graphql" %% "sangria-circe" % "1.3.2"
  )

  val boopickle = Seq("io.suzaku" %% "boopickle" % "1.5.0") // Updated for Scala 3 support

  val rocksDb = Seq(
    "org.rocksdb" % "rocksdbjni" % "10.10.1.1"
  )

  val enumeratum: Seq[ModuleID] = Seq(
    "com.beachape" %% "enumeratum" % "1.9.7",
    "com.beachape" %% "enumeratum-cats" % "1.9.7",
    "com.beachape" %% "enumeratum-scalacheck" % "1.9.7" % Test
  )

  val testing: Seq[ModuleID] = Seq(
    "org.scalatest" %% "scalatest" % "3.2.19" % "it,test",
    "org.scalamock" %% "scalamock" % "7.3.2" % "it,test",
    "org.scalatestplus" %% "scalacheck-1-18" % "3.2.19.0" % "test",
    "org.scalatestplus" %% "mockito-5-12" % "3.2.19.0" % "it,test",
    "org.mockito" % "mockito-core" % "5.23.0" % "it,test",
    "org.scalacheck" %% "scalacheck" % "1.19.0" % "it,test",
    "com.softwaremill.diffx" %% "diffx-core" % "0.9.0" % "test",
    "com.softwaremill.diffx" %% "diffx-scalatest" % "0.9.0" % "test"
  )

  val cats: Seq[ModuleID] = {
    val catsVersion = "2.13.0"
    val catsEffectVersion = "3.6.1"
    Seq(
      "org.typelevel" %% "mouse" % "1.4.0",
      "org.typelevel" %% "cats-core" % catsVersion,
      "org.typelevel" %% "cats-effect" % catsEffectVersion
    )
  }

  // Monix removed - fully migrated to Cats Effect 3 IO and fs2.Stream
  val monix = Seq.empty[ModuleID]

  val fs2: Seq[ModuleID] = {
    val fs2Version = "3.12.0" // requires cats-effect 3.6+
    Seq(
      "co.fs2" %% "fs2-core" % fs2Version,
      "co.fs2" %% "fs2-io" % fs2Version,
      "co.fs2" %% "fs2-reactive-streams" % fs2Version // For interop if needed
    )
  }

  // Scalanet is now vendored locally in scalanet/ directory
  // See scalanet/ATTRIBUTION.md for details
  val network: Seq[ModuleID] = Seq.empty

  // Dependencies for scalanet module
  val scodec: Seq[ModuleID] = Seq(
    "org.scodec" %% "scodec-core" % "2.3.3", // Stable with Scala 3 support
    "org.scodec" %% "scodec-bits" % "1.2.1"
  )

  val netty: Seq[ModuleID] = {
    val nettyVersion = "4.1.131.Final" // Updated for security (CVE-2024-29025, CVE-2024-47535 fixed)
    Seq(
      "io.netty" % "netty-handler" % nettyVersion,
      "io.netty" % "netty-handler-proxy" % nettyVersion, // For Socks5ProxyHandler
      "io.netty" % "netty-transport" % nettyVersion,
      "io.netty" % "netty-codec" % nettyVersion
    )
  }

  // Joda Time for DateTime (used in scalanet TLS extension)
  val jodaTime: Seq[ModuleID] = Seq(
    "joda-time" % "joda-time" % "2.12.7"
  )

  // IP math library for IP address range operations (used in scalanet)
  val ipmath: Seq[ModuleID] = Seq(
    "com.github.jgonian" % "commons-ip-math" % "1.32"
  )

  val logging = Seq(
    "ch.qos.logback" % "logback-classic" % "1.5.32",
    "com.typesafe.scala-logging" %% "scala-logging" % "3.9.6",
    "net.logstash.logback" % "logstash-logback-encoder" % "8.1",
    "org.codehaus.janino" % "janino" % "3.1.12",
    "org.typelevel" %% "log4cats-core" % "2.8.0",
    "org.typelevel" %% "log4cats-slf4j" % "2.8.0"
  )

  val crypto = Seq(
    "org.bouncycastle" % "bcprov-jdk18on" % "1.84",
    "org.bouncycastle" % "bcpkix-jdk18on" % "1.84",
    "tech.pegasys" % "jc-kzg-4844" % "1.0.0", // EIP-4844 KZG point evaluation (c-kzg-4844 JNI bindings)
    "org.hyperledger.besu" % "bls12-381" % "1.0.0" // EIP-2537 BLS12-381 precompiles (gnark/Constantine backends)
  )

  val scopt = Seq("com.github.scopt" %% "scopt" % "4.1.0") // Updated for Scala 3 support

  val cli = Seq("com.monovore" %% "decline" % "2.6.2")

  val apacheCommons = Seq(
    "commons-io" % "commons-io" % "2.22.0"
  )

  val apacheHttpClient = Seq(
    "org.apache.httpcomponents.client5" % "httpclient5" % "5.6.1" // For JupnP UPnP transport without URLStreamHandlerFactory
  )

  val jline = "org.jline" % "jline" % "3.30.13"

  val jna = "net.java.dev.jna" % "jna" % "5.18.1"

  val dependencies = Seq(
    jline,
    "org.scala-lang.modules" %% "scala-parser-combinators" % "2.4.0",
    "org.scala-sbt.ipcsocket" % "ipcsocket" % "1.6.3",
    "org.xerial.snappy" % "snappy-java" % "1.1.10.8",
    "org.web3j" % "core" % "4.14.0" % Test,
    "io.vavr" % "vavr" % "1.0.1",
    "org.jupnp" % "org.jupnp" % "3.0.4",
    "org.jupnp" % "org.jupnp.support" % "3.0.4",
    "org.jupnp" % "org.jupnp.tool" % "3.0.4",
    "javax.servlet" % "javax.servlet-api" % "4.0.1",
    "com.thesamet.scalapb" %% "scalapb-runtime" % "0.11.20"
  )

  val guava: Seq[ModuleID] = {
    val version = "33.6.0-jre"
    Seq(
      "com.google.guava" % "guava" % version,
      "com.google.guava" % "guava-testlib" % version % "test"
    )
  }

  // Prometheus Java client 1.x (replaces legacy simpleclient 0.x)
  val prometheus: Seq[ModuleID] = {
    val version = "1.3.5"
    Seq(
      "io.prometheus" % "prometheus-metrics-core" % version,
      "io.prometheus" % "prometheus-metrics-instrumentation-jvm" % version,
      "io.prometheus" % "prometheus-metrics-exporter-httpserver" % version
    )
  }

  val micrometer: Seq[ModuleID] = {
    val provider = "io.micrometer"
    val version = "1.16.5"
    Seq(
      // Required to compile metrics library https://github.com/micrometer-metrics/micrometer/issues/1133#issuecomment-452434205
      "com.google.code.findbugs" % "jsr305" % "3.0.2" % Optional,
      provider % "micrometer-core" % version,
      provider % "micrometer-registry-jmx" % version,
      provider % "micrometer-registry-prometheus" % version
    )
  }

  val kamon: Seq[ModuleID] = {
    val provider = "io.kamon"
    val version = "2.8.1"
    Seq(
      provider %% "kamon-prometheus" % version
      // Note: kamon-pekko not yet available, removed kamon-akka instrumentation
    )
  }

  val scaffeine: Seq[ModuleID] = Seq(
    "com.github.blemale" %% "scaffeine" % "5.3.0" % "compile",
    "com.github.ben-manes.caffeine" % "caffeine" % "3.2.4"
  )

}
