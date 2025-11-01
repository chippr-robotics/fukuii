import sbt._

object Dependencies {

  // Apache Pekko - Scala 3 compatible fork of Akka
  private val pekkoVersion = "1.1.2" // Latest stable Pekko version with Scala 3 support
  private val pekkoHttpVersion = "1.1.0" // Latest stable Pekko HTTP version

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

  val pekkoHttp: Seq[ModuleID] = {
    Seq(
      "org.apache.pekko" %% "pekko-http" % pekkoHttpVersion,
      "org.apache.pekko" %% "pekko-http-cors" % pekkoHttpVersion,
      "org.apache.pekko" %% "pekko-http-testkit" % pekkoHttpVersion % "it,test",
      // Note: pekko-http-json4s not yet available, using custom JSON marshalling with json4s
      "org.json4s" %% "json4s-native" % "4.0.7"
    )
  }

  val json4s = Seq("org.json4s" %% "json4s-native" % "4.0.7") // Updated for Scala 3 support

  val circe: Seq[ModuleID] = {
    val circeVersion = "0.14.10" // Stable with Scala 3 support

    Seq(
      "io.circe" %% "circe-core" % circeVersion,
      "io.circe" %% "circe-generic" % circeVersion,
      "io.circe" %% "circe-parser" % circeVersion
      // NOTE: circe-generic-extras is deprecated and not available for Scala 3
      // Functionality has been integrated into circe-generic in 0.14.x
      // See: https://github.com/circe/circe-generic-extras/issues/276
    )
  }

  val boopickle = Seq("io.suzaku" %% "boopickle" % "1.4.0") // Updated for Scala 3 support

  val rocksDb = Seq(
    "org.rocksdb" % "rocksdbjni" % "8.11.4" // Stable version
  )

  val enumeratum: Seq[ModuleID] = Seq(
    "com.beachape" %% "enumeratum" % "1.7.5", // Stable with Scala 3 support
    "com.beachape" %% "enumeratum-cats" % "1.7.5",
    "com.beachape" %% "enumeratum-scalacheck" % "1.7.5" % Test
  )

  val testing: Seq[ModuleID] = Seq(
    "org.scalatest" %% "scalatest" % "3.2.19" % "it,test", // Updated for Scala 3 support
    "org.scalamock" %% "scalamock" % "6.0.0" % "it,test", // Updated for Scala 3 support
    "org.scalatestplus" %% "scalacheck-1-18" % "3.2.19.0" % "test", // Updated for ScalaCheck 1.18
    "org.scalacheck" %% "scalacheck" % "1.18.1" % "it,test", // Updated for Scala 3 support
    "com.softwaremill.diffx" %% "diffx-core" % "0.9.0" % "test", // Updated for Scala 3 support
    "com.softwaremill.diffx" %% "diffx-scalatest" % "0.9.0" % "test"
  )

  val cats: Seq[ModuleID] = {
    val catsVersion = "2.10.0" // Stable with Scala 3 support
    val catsEffectVersion = "3.5.4" // Stable Cats Effect 3 with Scala 3 support
    Seq(
      "org.typelevel" %% "mouse" % "1.2.3", // Stable with Scala 3 support
      "org.typelevel" %% "cats-core" % catsVersion,
      "org.typelevel" %% "cats-effect" % catsEffectVersion
    )
  }

  // Monix removed - fully migrated to Cats Effect 3 IO and fs2.Stream
  val monix = Seq.empty[ModuleID]

  val fs2: Seq[ModuleID] = {
    val fs2Version = "3.10.2" // Stable with CE3 and Scala 3 support
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
    val nettyVersion = "4.1.115.Final" // Updated for security (CVE-2024-29025, CVE-2024-47535 fixed)
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
    "ch.qos.logback" % "logback-classic" % "1.5.12", // Stable version
    "com.typesafe.scala-logging" %% "scala-logging" % "3.9.5",
    "net.logstash.logback" % "logstash-logback-encoder" % "8.0",
    "org.codehaus.janino" % "janino" % "3.1.12",
    "org.typelevel" %% "log4cats-core" % "2.6.0", // Stable with Scala 3 support
    "org.typelevel" %% "log4cats-slf4j" % "2.6.0"
  )

  val crypto = Seq(
    "org.bouncycastle" % "bcprov-jdk18on" % "1.82", // Updated for JDK 18+ compatibility (jdk15on artifacts discontinued)
    "org.bouncycastle" % "bcpkix-jdk18on" % "1.82"  // Additional bouncy castle package for X.509 certificates
  )

  val scopt = Seq("com.github.scopt" %% "scopt" % "4.1.0") // Updated for Scala 3 support

  val cli = Seq("com.monovore" %% "decline" % "2.4.1") // Updated for Scala 3 support

  val apacheCommons = Seq(
    "commons-io" % "commons-io" % "2.16.1" // Stable version
  )

  val jline = "org.jline" % "jline" % "3.26.1" // Stable version

  val jna = "net.java.dev.jna" % "jna" % "5.14.0" // Stable version

  val dependencies = Seq(
    jline,
    "org.scala-lang.modules" %% "scala-parser-combinators" % "2.4.0",
    "org.scala-sbt.ipcsocket" % "ipcsocket" % "1.6.2", // Stable version
    "org.xerial.snappy" % "snappy-java" % "1.1.10.5", // Stable version
    "org.web3j" % "core" % "4.9.8" % Test, // Stable version without jc-kzg-4844 dependency issues
    "io.vavr" % "vavr" % "1.0.0-alpha-4", // Latest alpha
    "org.jupnp" % "org.jupnp" % "3.0.2", // Stable version
    "org.jupnp" % "org.jupnp.support" % "3.0.2",
    "org.jupnp" % "org.jupnp.tool" % "3.0.2",
    "javax.servlet" % "javax.servlet-api" % "4.0.1",
    "com.thesamet.scalapb" %% "scalapb-runtime" % "0.11.17"
  )

  val guava: Seq[ModuleID] = {
    val version = "33.0.0-jre" // Stable version
    Seq(
      "com.google.guava" % "guava" % version,
      "com.google.guava" % "guava-testlib" % version % "test"
    )
  }

  val prometheus: Seq[ModuleID] = {
    val provider = "io.prometheus"
    val version = "0.16.0" // Stable version
    Seq(
      provider % "simpleclient" % version,
      provider % "simpleclient_logback" % version,
      provider % "simpleclient_hotspot" % version,
      provider % "simpleclient_httpserver" % version
    )
  }

  val micrometer: Seq[ModuleID] = {
    val provider = "io.micrometer"
    val version = "1.13.0" // Stable version
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
    val version = "2.7.5" // Stable with Scala 3 support
    Seq(
      provider %% "kamon-prometheus" % version
      // Note: kamon-pekko not yet available, removed kamon-akka instrumentation
    )
  }



  val scaffeine: Seq[ModuleID] = Seq(
    "com.github.blemale" %% "scaffeine" % "5.3.0" % "compile", // Updated for Scala 3 support
    "com.github.ben-manes.caffeine" % "caffeine" % "3.1.8" // Explicit caffeine dependency for scalanet
  )

}
