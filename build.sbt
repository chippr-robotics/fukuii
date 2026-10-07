enablePlugins(JavaAppPackaging, SolidityPlugin, JavaAgent)

javaAgents += "io.kamon" % "kanela-agent" % "1.0.18"

import scala.sys.process.Process
import NativePackagerHelper._
import com.github.sbt.git.SbtGit.git

// Necessary for the nix build, please do not remove.
val nixBuild = sys.props.isDefinedAt("nix")

// Enable dev mode: disable certain flags, etc.
val fukuiiDev = sys.props.get("fukuiiDev").contains("true") || sys.env.get("FUKUII_DEV").contains("true")

// Scala 3 has a different optimizer, no explicit optimization flags needed
lazy val scala3OptimizationsForProd = Seq.empty[String]

// Releasing. https://github.com/olafurpg/sbt-ci-release
inThisBuild(
  List(
    homepage := Some(url("https://github.com/chippr-robotics/fukuii")),
    scmInfo := Some(
      ScmInfo(url("https://github.com/chippr-robotics/fukuii"), "git@github.com:chippr-robotics/fukuii.git")
    ),
    licenses := List("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")),
    developers := List(),
    scalaVersion := `scala-3`, // must be in inThisBuild for scalafixSemanticdb.revision
    semanticdbEnabled := true, // required for scalafix semantic rules
    semanticdbVersion := scalafixSemanticdb.revision,
    // Add reliable resolvers to avoid transient HTTP 503 errors
    resolvers ++= Seq(
      Resolver.mavenCentral,
      "Typesafe Releases".at("https://repo.typesafe.com/typesafe/releases/"),
      "Sonatype OSS Releases".at("https://oss.sonatype.org/content/repositories/releases/"),
      "Sonatype OSS Snapshots".at("https://oss.sonatype.org/content/repositories/snapshots/"),
      "Hyperledger Besu".at("https://hyperledger.jfrog.io/artifactory/besu-maven/")
    )
  )
)

// https://github.com/sbt/sbt/issues/3570
updateOptions := updateOptions.value.withGigahorse(false)

// artifact name will include scala version
crossPaths := true

// patch for error on 'early-semver' problems
ThisBuild / evictionErrorLevel := Level.Info

val `scala-3` = "3.3.8" // 3.3.8 released 2026-06-11, latest 3.3 LTS patch
val supportedScalaVersions = List(`scala-3`) // Scala 3 only

// Base scalac options
val baseScalacOptions = Seq(
  "-unchecked",
  "-deprecation",
  "-feature",
  "-encoding",
  "utf-8"
)

// Scala 3 warning and feature options
val scala3Options = Seq(
  "-source:future", // Enforce Scala 3 future syntax (import x.* required, not import x._)
  "-language:adhocExtensions", // Allow extending non-open classes (TestKit, Pekko test patterns)
  "-Wunused:all", // Enable unused warnings for Scala 3 (required for scalafix)
  "-Wconf:id=E198:error", // Ratchet step 4/4: unused symbols are build errors (Scala 3: id=E198, cat=unused is not valid)
  "-Wconf:cat=feature:s", // Suppress adhocExtensions feature warnings (extending non-open Pekko/library classes)
  "-Wconf:cat=unchecked:error", // Ratchet step 4/4: unchecked patterns are build errors
  "-Ykind-projector", // Scala 3 replacement for kind-projector plugin
  "-Xmax-inlines:64" // Increase inline depth limit for complex boopickle/circe derivations
)

def commonSettings(projectName: String): Seq[sbt.Def.Setting[_]] = Seq(
  name := projectName,
  organization := "com.chipprbots",
  scalaVersion := `scala-3`,
  // Override Scala library version to prevent SIP-51 errors with mixed Scala patch versions
  scalaModuleInfo ~= (_.map(_.withOverrideScalaVersion(true))),
  // organize-imports removed — built-in to Scalafix 0.11.0+
  // Scalanet snapshots are published to Sonatype after each build (now defined in inThisBuild resolvers).
  (Test / testOptions) += Tests
    .Argument(TestFrameworks.ScalaTest, "-l", "EthashMinerSpec"), // miner tests disabled by default
  (Test / testOptions) += Tests
    .Argument(TestFrameworks.ScalaTest, "-l", "IntegrationTest"), // network-dependent tests excluded by default
  // The `it` config extends Test, so without this it inherits the `-l IntegrationTest` exclusion above and silently
  // skips every suite tagged IntegrationTest — `IntegrationTest / test`, `pp`, `testStandard` and `testComprehensive`
  // were running only the untagged suites (EthSmokeSpec). Keep every other Test option; drop only that exclusion.
  (Integration / testOptions) := (Test / testOptions).value.filterNot {
    case Tests.Argument(_, args) => args == List("-l", "IntegrationTest")
    case _                       => false
  },
  // Configure scalacOptions for Scala 3
  scalacOptions := {
    val base = baseScalacOptions
    val optimizations = if (fukuiiDev) Seq.empty else scala3OptimizationsForProd
    base ++ scala3Options ++ optimizations
  },
  (Compile / console / scalacOptions) ~= (_.filterNot(
    Set(
      "-Xfatal-warnings"
    )
  )),
  (Compile / doc / scalacOptions) := baseScalacOptions ++ Seq(
    "-no-link-warnings" // Suppress link resolution warnings for F-bounded polymorphism issues
  ),
  scalacOptions ~= (options => if (fukuiiDev) options.filterNot(_ == "-Xfatal-warnings") else options),
  Test / parallelExecution := true,
  Test / fork := true, // Fork JVM for tests to ensure clean shutdown and avoid resource leak issues
  Test / javaOptions ++= Seq(
    "-Dpekko.coordinated-shutdown.exit-jvm=off", // Prevent CoordinatedShutdown from calling System.exit
    "-Dpekko.coordinated-shutdown.run-by-actor-system-terminate=on", // Ensure proper shutdown on ActorSystem.terminate
    "-Dpekko.jvm-shutdown-hooks=off" // Disable Pekko JVM shutdown hooks that may interfere with test cleanup
  ),
  Test / testForkedParallel := false, // Run tests sequentially in the forked JVM to avoid resource contention
  Test / logBuffered := false, // Stream forked JVM test output immediately rather than buffering per-suite
  (Test / testOptions) += Tests.Argument("-oDG"),
  // Ensure JUnit XML report directory exists after `sbt clean` deletes it (forked JVM writes there)
  (Test / testOptions) += {
    val reportsDir = (Test / target).value / "test-reports"
    Tests.Setup(_ => IO.createDirectory(reportsDir))
  },
  // Only publish selected libraries.
  (publish / skip) := true
)

val publishSettings = Seq(
  publish / skip := false,
  crossScalaVersions := supportedScalaVersions // Scala 3 only
)

// Adding an "it" config because in `Dependencies.scala` some are declared with `% "it,test"`
// which would fail if the project didn't have configuration to add to.
val Integration = config("it").extend(Test)

// Vendored scalanet modules (from IOHK's scalanet library)
lazy val scalanet = {
  val scalanet = project
    .in(file("scalanet"))
    .configs(Integration)
    .settings(commonSettings("fukuii-scalanet"))
    .settings(inConfig(Integration)(scalafixConfigSettings(Integration)))
    .settings(publishSettings)
    .settings(
      Compile / unmanagedSourceDirectories += baseDirectory.value / "src",
      Test / unmanagedSourceDirectories += baseDirectory.value / "ut" / "src",
      libraryDependencies ++=
        Dependencies.pekko ++
          Dependencies.cats ++
          Dependencies.fs2 ++
          Dependencies.monix ++
          Dependencies.scodec ++
          Dependencies.netty ++
          Dependencies.crypto ++
          Dependencies.jodaTime ++
          Dependencies.ipmath ++
          Dependencies.scaffeine ++
          Dependencies.logging ++
          Dependencies.testing
    )

  scalanet
}

lazy val scalanetDiscovery = {
  val scalanetDiscovery = project
    .in(file("scalanet/discovery"))
    .configs(Integration)
    .dependsOn(scalanet)
    .settings(commonSettings("fukuii-scalanet-discovery"))
    .settings(inConfig(Integration)(scalafixConfigSettings(Integration)))
    .settings(publishSettings)
    .settings(
      Compile / unmanagedSourceDirectories += baseDirectory.value / "src",
      Integration / unmanagedSourceDirectories += baseDirectory.value / "it" / "src",
      Test / unmanagedSourceDirectories += baseDirectory.value / "ut" / "src",
      libraryDependencies ++=
        Dependencies.pekko ++
          Dependencies.cats ++
          Dependencies.fs2 ++
          Dependencies.monix ++
          Dependencies.scodec ++
          Dependencies.netty ++
          Dependencies.crypto ++
          Dependencies.jodaTime ++
          Dependencies.ipmath ++
          Dependencies.scaffeine ++
          Dependencies.logging ++
          Dependencies.testing
    )

  scalanetDiscovery
}

lazy val bytes = {
  val bytes = project
    .in(file("bytes"))
    .configs(Integration)
    .settings(commonSettings("fukuii-bytes"))
    .settings(inConfig(Integration)(scalafixConfigSettings(Integration)))
    .settings(publishSettings)
    .settings(
      libraryDependencies ++=
        Dependencies.pekkoUtil ++
          Dependencies.testing
    )

  bytes
}

lazy val crypto = {
  val crypto = project
    .in(file("crypto"))
    .configs(Integration)
    .dependsOn(bytes)
    .settings(commonSettings("fukuii-crypto"))
    .settings(inConfig(Integration)(scalafixConfigSettings(Integration)))
    .settings(publishSettings)
    .settings(
      libraryDependencies ++=
        Dependencies.pekkoUtil ++
          Dependencies.crypto ++
          Dependencies.testing
    )

  crypto
}

lazy val rlp = {
  val rlp = project
    .in(file("rlp"))
    .configs(Integration)
    .dependsOn(bytes)
    .settings(commonSettings("fukuii-rlp"))
    .settings(inConfig(Integration)(scalafixConfigSettings(Integration)))
    .settings(publishSettings)
    .settings(
      libraryDependencies ++=
        Dependencies.pekkoUtil ++
          Dependencies.testing
    )

  rlp
}

lazy val node = {
  val Benchmark = config("benchmark").extend(Test)

  val Evm = config("evm").extend(Test)

  val Rpc = config("rpcTest").extend(Test)

  val malletDeps = Seq(
    Dependencies.scopt
  ).flatten ++ Seq(
    Dependencies.jline,
    Dependencies.jna
  )

  val dep =
    Seq(
      Dependencies.pekko,
      Dependencies.pekkoHttp,
      Dependencies.apacheCommons,
      Dependencies.apacheHttpClient,
      Dependencies.boopickle,
      Dependencies.cats,
      Dependencies.circe,
      Dependencies.cli,
      Dependencies.crypto,
      Dependencies.dependencies,
      Dependencies.enumeratum,
      Dependencies.fs2,
      Dependencies.guava,
      Dependencies.json4s,
      Dependencies.kamon,
      Dependencies.logging,
      Dependencies.micrometer,
      Dependencies.monix,
      Dependencies.network,
      Dependencies.prometheus,
      Dependencies.rocksDb,
      Dependencies.sangria,
      Dependencies.scaffeine,
      Dependencies.scopt,
      Dependencies.testing
    ).flatten ++ malletDeps

  (Evm / test) := (Evm / test).dependsOn(solidityCompile).value
  (Evm / sourceDirectory) := baseDirectory.value / "src" / "evmTest"

  val node = project
    .in(file("."))
    .configs(Integration, Benchmark, Evm, Rpc)
    .enablePlugins(BuildInfoPlugin)
    .dependsOn(bytes, crypto, rlp, scalanet, scalanetDiscovery)
    .settings(
      buildInfoKeys ++= Seq[BuildInfoKey](
        name,
        version,
        scalaVersion,
        sbtVersion,
        // engine_getClientVersionV1 requires `commit` to be exactly 8 hex chars
        // (4 bytes per execution-apis spec); 7 trips Lighthouse's length check.
        BuildInfoKey.action("gitHeadCommit")(git.gitHeadCommit.?.value.flatten.map(_.take(8)).getOrElse("unknown")),
        BuildInfoKey.action("gitCurrentBranch") {
          val branch = git.gitCurrentBranch.?.value.getOrElse("")
          if (branch != null && branch.nonEmpty) branch else "unknown"
        },
        BuildInfoKey.action("gitCurrentTags")(git.gitCurrentTags.?.value.getOrElse(Seq.empty).mkString(",")),
        BuildInfoKey.action("gitDescribedVersion")(git.gitDescribedVersion.?.value.flatten.getOrElse("unknown")),
        BuildInfoKey.action("gitUncommittedChanges")(git.gitUncommittedChanges.?.value.getOrElse(false)),
        (Compile / libraryDependencies)
      ),
      buildInfoPackage := "com.chipprbots.ethereum.utils",
      (Test / fork) := true,
      (Compile / buildInfoOptions) += BuildInfoOption.ToMap
    )
    .settings(commonSettings("fukuii"): _*)
    .settings(inConfig(Integration)(scalafixConfigSettings(Integration)))
    .settings(inConfig(Evm)(scalafixConfigSettings(Evm)))
    .settings(inConfig(Rpc)(scalafixConfigSettings(Rpc)))
    .settings(
      libraryDependencies ++= dep
    )
    .settings(
      executableScriptName := name.value
    )
    .settings(
      inConfig(Integration)(
        Defaults.testSettings
          ++ org.scalafmt.sbt.ScalafmtPlugin.scalafmtConfigSettings
          :+ (parallelExecution := false)
          :+ (testGrouping := {
            val tests = (definedTests).value
            tests.map { test =>
              Tests.Group(
                name = test.name,
                tests = Seq(test),
                runPolicy = Tests.SubProcess(
                  ForkOptions().withRunJVMOptions(
                    Vector(s"-DFUKUII_TEST_ID=${System.currentTimeMillis()}-${test.name.hashCode.abs}")
                  )
                )
              )
            }
          })
      ): _*
    )
    .settings(inConfig(Benchmark)(Defaults.testSettings :+ (Test / parallelExecution := true)): _*)
    .settings(inConfig(Evm)(Defaults.testSettings :+ (Test / parallelExecution := true)): _*)
    .settings(inConfig(Rpc)(Defaults.testSettings :+ (Test / parallelExecution := true)): _*)
    .settings(
      // Packaging
      maintainer := "chippr-robotics@github.com",
      (Compile / mainClass) := Some("com.chipprbots.ethereum.App"),
      (Compile / discoveredMainClasses) := Seq(),
      (Universal / mappings) ++= directory((Compile / resourceDirectory).value / "conf"),
      (Universal / mappings) += (Compile / resourceDirectory).value / "logback.xml" -> "conf/logback.xml",
      bashScriptExtraDefines += """addJava "-Dconfig.file=${app_home}/../conf/app.conf"""",
      bashScriptExtraDefines += """addJava "-Dlogback.configurationFile=${app_home}/../conf/logback.xml"""",
      batScriptExtraDefines += """call :add_java "-Dconfig.file=%APP_HOME%\conf\app.conf"""",
      batScriptExtraDefines += """call :add_java "-Dlogback.configurationFile=%APP_HOME%\conf\logback.xml"""",
      // Keep sbt-native-packager Docker builds on the same supported runtime as the hand-written Dockerfiles.
      // Without this, the plugin default generated `FROM openjdk:8`, which Docker Hub no longer serves.
      dockerBaseImage := "eclipse-temurin:25-jre-noble",
      // Use a wildcard classpath ("lib/*") instead of enumerating every
      // jar by name. The default sbt-native-packager behaviour wrote
      // ~12KB of `-cp lib/jar1;lib/jar2;...` into bin/fukuii.bat (147
      // jars), exceeding cmd.exe's ~8KB command-line limit on Windows
      // so users got "input line is too long / syntax of the command is
      // incorrect" before the JVM even launched. Java accepts `*` as a
      // classpath glob on every supported platform, so this also keeps
      // bin/fukuii (bash) working.
      scriptClasspath := Seq("*"),
      // Assembly configuration
      (assembly / mainClass) := Some("com.chipprbots.ethereum.App"),
      (assembly / assemblyJarName) := s"fukuii-assembly-${version.value}.jar",
      (assembly / assemblyMergeStrategy) := {
        case PathList("META-INF", "MANIFEST.MF")                                       => MergeStrategy.discard
        case PathList("META-INF", xs @ _*) if xs.lastOption.exists(_.endsWith(".SF"))  => MergeStrategy.discard
        case PathList("META-INF", xs @ _*) if xs.lastOption.exists(_.endsWith(".DSA")) => MergeStrategy.discard
        case PathList("META-INF", xs @ _*) if xs.lastOption.exists(_.endsWith(".RSA")) => MergeStrategy.discard
        case PathList("META-INF", "io.netty.versions.properties")                      => MergeStrategy.first
        case PathList("META-INF", "native", xs @ _*)                                   => MergeStrategy.first
        case PathList("META-INF", "native-image", xs @ _*)                             => MergeStrategy.first
        case PathList("META-INF", "versions", xs @ _*)                                 => MergeStrategy.first
        case "module-info.class"                                                       => MergeStrategy.discard
        case "reference.conf"                                                          => MergeStrategy.concat
        case "application.conf"                                                        => MergeStrategy.concat
        case x if x.endsWith(".proto")                                                 => MergeStrategy.first
        case x if x.contains("pekko")                                                  => MergeStrategy.first
        case x if x.contains("akka")                                                   => MergeStrategy.first
        case _                                                                         => MergeStrategy.first
      }
    )

  node

}

// Scoverage configuration
coverageEnabled := false // Disabled by default, enable with `sbt coverage`
coverageMinimumStmtTotal := 70
coverageFailOnMinimum := true
coverageHighlighting := true
coverageExcludedPackages := Seq(
  "com\\.chipprbots\\.ethereum\\.utils\\.BuildInfo" // BuildInfo generated code
).mkString(";")
coverageExcludedFiles := Seq(
  ".*/src_managed/.*", // All managed sources
  ".*/target/.*/src_managed/.*" // Target managed sources
).mkString(";")

addCommandAlias(
  "compile-all",
  """; bytes / compile
    |; bytes / Test / compile
    |; crypto / compile
    |; crypto / Test / compile
    |; rlp / compile
    |; rlp / Test / compile
    |; compile
    |; Test / compile
    |; Evm / compile
    |; IntegrationTest / compile
    |; RpcTest / compile
    |; Benchmark / compile
    |""".stripMargin
)

// prepare PR
addCommandAlias(
  "pp",
  """; compile-all
    |; bytes / scalafmtAll
    |; crypto / scalafmtAll
    |; rlp / scalafmtAll
    |; scalafmtAll
    |; rlp / test
    |; testQuick
    |; IntegrationTest / test
    |""".stripMargin
)

// format all modules
addCommandAlias(
  "formatAll",
  """; compile-all
    |; bytes / scalafixAll
    |; bytes / scalafmtAll
    |; crypto / scalafixAll
    |; crypto / scalafmtAll
    |; rlp / scalafixAll
    |; rlp / scalafmtAll
    |; scalafixAll
    |; scalafmtAll
    |""".stripMargin
)

// check modules formatting
addCommandAlias(
  "formatCheck",
  """; compile-all
    |; bytes / scalafixAll --check
    |; bytes / scalafmtCheckAll
    |; crypto / scalafixAll --check
    |; crypto / scalafmtCheckAll
    |; rlp / scalafixAll --check
    |; rlp / scalafmtCheckAll
    |; scalafixAll --check
    |; scalafmtCheckAll
    |""".stripMargin
)

// testAll
addCommandAlias(
  "testAll",
  """; compile-all
    |; rlp / test
    |; bytes / test
    |; crypto / test
    |; test
    |; IntegrationTest / test
    |""".stripMargin
)

// runScapegoat - Run scapegoat analysis on all modules
// Re-enabled with Scala 3 compatible version 2.x/3.x
addCommandAlias(
  "runScapegoat",
  """; compile-all
    |; bytes / scapegoat
    |; crypto / scapegoat
    |; rlp / scapegoat
    |; scapegoat
    |""".stripMargin
)

// testCoverage - Run tests with coverage
addCommandAlias(
  "testCoverage",
  """; coverage
    |; testAll
    |; coverageReport
    |; coverageAggregate
    |""".stripMargin
)

// testCoverageOff - Run tests without coverage (cleanup)
addCommandAlias(
  "testCoverageOff",
  """; coverageOff
    |; testAll
    |""".stripMargin
)

// ===== Test Tagging Commands (ADR-017) =====
// These commands enable selective test execution based on ScalaTest tags

// testEssential - Tier 1: Essential tests (< 5 minutes)
// Runs fast unit tests, excludes integration and slow tests.
// SlowTest: legitimately too slow for the daily commit gate (runs in testStandard).
// IntegrationTest: network-dependent or actor-choreography tests that belong in Tier 2+.
// SyncTest: complex actor choreography (ADR-017) that times out under CI load.
// DisabledTest: tests explicitly turned off (timing-flaky / WIP) — the tag exists to keep
//   them out of the gate; see the silenced-test inventory before un-silencing.
addCommandAlias(
  "testEssential",
  """; compile-all
    |; testOnly -- -l SlowTest -l IntegrationTest -l SyncTest -l DisabledTest
    |; rlp / test
    |; bytes / test
    |; crypto / test
    |""".stripMargin
)

// testStandard - Tier 2: Standard tests (< 30 minutes)
// Runs unit and integration tests. Excludes only Tier 3 tests:
// BenchmarkTest/EthereumTest: the 3-hour compliance suite — belongs in testComprehensive only.
addCommandAlias(
  "testStandard",
  """; compile-all
    |; testOnly -- -l BenchmarkTest -l EthereumTest -l SyncTest -l DisabledTest
    |""".stripMargin
)

// testComprehensive - Tier 3: Comprehensive tests (< 3 hours)
// Runs all tests including the ethereum/tests compliance suite. No exclusions.
addCommandAlias(
  "testComprehensive",
  """; compile-all
    |; rlp / test
    |; bytes / test
    |; crypto / test
    |; testOnly
    |; IntegrationTest / testOnly
    |""".stripMargin
)

// testEthSmoke - Fast ETH-path smoke target (< 60s)
// Runs only EthSmoke-tagged vectors in the IntegrationTest config to exercise the ETH
// execution path (chainId=1, forTimestamp dispatch) below testComprehensive.
// Mirrors the testEssential pattern (compile-all + filtered testOnly), but scoped to
// IntegrationTest and using an inclusion filter (-n EthSmoke) instead of exclusions.
addCommandAlias(
  "testEthSmoke",
  """; compile-all
    |; IntegrationTest / testOnly -- -n EthSmoke
    |""".stripMargin
)

// Module-specific test commands
addCommandAlias("testCrypto", "testOnly -- -n CryptoTest")
addCommandAlias("testVM", "testOnly -- -n VMTest")
addCommandAlias("testNetwork", "testOnly -- -n NetworkTest")
addCommandAlias("testDatabase", "testOnly -- -n DatabaseTest")
addCommandAlias("testRLP", "testOnly -- -n RLPTest")
addCommandAlias("testMPT", "testOnly -- -n MPTTest")
addCommandAlias("testEthereum", "testOnly -- -n EthereumTest")
// Domain test commands — added in P12 (tag taxonomy audit)
// ConsensusTest (284), RPCTest (219), OlympiaTest (201), StateTest (63), SyncTest (84)
addCommandAlias("testConsensus", "testOnly -- -n ConsensusTest")
addCommandAlias("testRPC", "testOnly -- -n RPCTest")
addCommandAlias("testState", "testOnly -- -n StateTest")
addCommandAlias("testOlympia", "testOnly -- -n OlympiaTest")
addCommandAlias("testSync", "testOnly -- -n SyncTest")

// Scapegoat configuration for Scala 3
(ThisBuild / scapegoatVersion) := "3.3.6" // first cross-build for Scala 3.3.8
scapegoatReports := Seq("xml", "html")
scapegoatConsoleOutput := false
scapegoatDisabledInspections := Seq("UnsafeTraversableMethods")
scapegoatIgnoredFiles := Seq(
  ".*/src_managed/.*",
  ".*/BuildInfo\\.scala"
)
