import sbt._

object Dependencies {

  private val akkaVersion = "2.6.20" // Updated for Scala 3 support (minimum version with Scala 3 artifacts)

  val akkaUtil: Seq[ModuleID] =
    Seq(
      "com.typesafe.akka" %% "akka-actor" % akkaVersion
    )

  val akka: Seq[ModuleID] =
    Seq(
      "com.typesafe.akka" %% "akka-actor" % akkaVersion,
      "com.typesafe.akka" %% "akka-actor-typed" % akkaVersion,
      "com.typesafe.akka" %% "akka-slf4j" % akkaVersion,
      "com.typesafe.akka" %% "akka-actor-typed" % akkaVersion,
      "com.typesafe.akka" %% "akka-testkit" % akkaVersion,
      "com.typesafe.akka" %% "akka-actor-testkit-typed" % akkaVersion,
      "com.typesafe.akka" %% "akka-stream" % akkaVersion
      // NOTE: akka-mock-scheduler 0.5.5 is not available for Scala 3
      // Commented out temporarily - tests that use this will need to be updated or skipped
      // "com.miguno.akka" %% "akka-mock-scheduler" % "0.5.5" % "it,test"
    )

  val akkaHttp: Seq[ModuleID] = {
    val akkaHttpVersion = "10.2.10" // Updated for Scala 3 support

    Seq(
      "com.typesafe.akka" %% "akka-http" % akkaHttpVersion,
      "ch.megard" %% "akka-http-cors" % "1.1.0",
      "de.heikoseeberger" %% "akka-http-json4s" % "1.39.2", // Updated for json4s 4.0.x compatibility
      "com.typesafe.akka" %% "akka-http-testkit" % akkaHttpVersion % "it,test"
    )
  }

  val json4s = Seq("org.json4s" %% "json4s-native" % "4.0.7") // Updated for Scala 3 support

  val circe: Seq[ModuleID] = {
    val circeVersion = "0.14.10" // Updated for Scala 3 support
    val circeGenericExtrasVersion = "0.14.4" // Last version with generic-extras

    Seq(
      "io.circe" %% "circe-core" % circeVersion,
      "io.circe" %% "circe-generic" % circeVersion,
      "io.circe" %% "circe-parser" % circeVersion,
      "io.circe" %% "circe-generic-extras" % circeGenericExtrasVersion // Separate versioning for generic-extras
    )
  }

  val boopickle = Seq("io.suzaku" %% "boopickle" % "1.4.0") // Updated for Scala 3 support

  val rocksDb = Seq(
    // use "5.18.3" for older macOS
    "org.rocksdb" % "rocksdbjni" % "6.15.2"
  )

  val enumeratum: Seq[ModuleID] = Seq(
    "com.beachape" %% "enumeratum" % "1.7.5", // Updated for Scala 3 support
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
    val catsVersion = "2.9.0" // Updated for Scala 3 support, compatible with 2.13.6
    val catsEffectVersion = "3.5.4" // Cats Effect 3 - required for full Scala 3 support
    Seq(
      "org.typelevel" %% "mouse" % "1.2.1", // Compatible with Scala 2.13.6 and 3.x
      "org.typelevel" %% "cats-core" % catsVersion,
      "org.typelevel" %% "cats-effect" % catsEffectVersion // CE3 upgrade - see docs/CATS_EFFECT_3_MIGRATION.md
    )
  }

  val monix = Seq(
    "io.monix" %% "monix" % "3.4.1" // Updated for partial Scala 3 support
  )

  val fs2: Seq[ModuleID] = {
    val fs2Version = "3.9.3" // Latest stable with CE3 support
    Seq(
      "co.fs2" %% "fs2-core" % fs2Version,
      "co.fs2" %% "fs2-io" % fs2Version,
      "co.fs2" %% "fs2-reactive-streams" % fs2Version // For interop if needed
    )
  }

  // Scalanet is now vendored locally in scalanet/ directory
  // See scalanet/ATTRIBUTION.md for details
  val network: Seq[ModuleID] = Seq.empty

  val logging = Seq(
    "ch.qos.logback" % "logback-classic" % "1.5.12", // Updated for better compatibility
    "com.typesafe.scala-logging" %% "scala-logging" % "3.9.5", // Updated for Scala 3 support
    "net.logstash.logback" % "logstash-logback-encoder" % "8.0", // Updated
    "org.codehaus.janino" % "janino" % "3.1.12", // Updated for security
    "org.typelevel" %% "log4cats-core" % "2.6.0", // Updated for Cats Effect 3.x and Scala 3 support
    "org.typelevel" %% "log4cats-slf4j" % "2.6.0" // Updated for Cats Effect 3.x and Scala 3 support
  )

  val crypto = Seq("org.bouncycastle" % "bcprov-jdk15on" % "1.66")

  val scopt = Seq("com.github.scopt" %% "scopt" % "4.1.0") // Updated for Scala 3 support

  val cli = Seq("com.monovore" %% "decline" % "2.4.1") // Updated for Scala 3 support

  val apacheCommons = Seq(
    "commons-io" % "commons-io" % "2.8.0"
  )

  val jline = "org.jline" % "jline" % "3.16.0"

  val jna = "net.java.dev.jna" % "jna" % "5.6.0"

  val dependencies = Seq(
    jline,
    "org.scala-lang.modules" %% "scala-parser-combinators" % "1.1.2",
    "org.scala-sbt.ipcsocket" % "ipcsocket" % "1.1.0",
    "org.xerial.snappy" % "snappy-java" % "1.1.7.7",
    "org.web3j" % "core" % "4.5.11" % Test,
    "io.vavr" % "vavr" % "1.0.0-alpha-3",
    "org.jupnp" % "org.jupnp" % "2.5.2",
    "org.jupnp" % "org.jupnp.support" % "2.5.2",
    "org.jupnp" % "org.jupnp.tool" % "2.5.2",
    "javax.servlet" % "javax.servlet-api" % "4.0.1"
  )

  val guava: Seq[ModuleID] = {
    val version = "30.1-jre"
    Seq(
      "com.google.guava" % "guava" % version,
      "com.google.guava" % "guava-testlib" % version % "test"
    )
  }

  val prometheus: Seq[ModuleID] = {
    val provider = "io.prometheus"
    val version = "0.9.0"
    Seq(
      provider % "simpleclient" % version,
      provider % "simpleclient_logback" % version,
      provider % "simpleclient_hotspot" % version,
      provider % "simpleclient_httpserver" % version
    )
  }

  val micrometer: Seq[ModuleID] = {
    val provider = "io.micrometer"
    val version = "1.5.5"
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
    val version = "2.7.5" // Updated for Scala 3 support
    Seq(
      provider %% "kamon-prometheus" % version,
      provider %% "kamon-akka" % version
    )
  }

  val shapeless: Seq[ModuleID] = Seq(
    "org.typelevel" %% "shapeless3-deriving" % "3.4.3"
  )

  val scaffeine: Seq[ModuleID] = Seq(
    "com.github.blemale" %% "scaffeine" % "5.3.0" % "compile" // Updated for Scala 3 support
  )

}
