enablePlugins(JavaAppPackaging, SolidityPlugin, JavaAgent)

javaAgents += "io.kamon" % "kanela-agent" % "1.0.6"

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
    homepage := Some(url("https://github.com/chippr-robotics/chordodes_fukuii")),
    scmInfo := Some(
      ScmInfo(url("https://github.com/chippr-robotics/chordodes_fukuii"), "git@github.com:chippr-robotics/chordodes_fukuii.git")
    ),
    licenses := List("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")),
    developers := List(),
    // Add reliable resolvers to avoid transient HTTP 503 errors
    resolvers ++= Seq(
      Resolver.mavenCentral,
      "Typesafe Releases" at "https://repo.typesafe.com/typesafe/releases/",
      "Sonatype OSS Releases" at "https://oss.sonatype.org/content/repositories/releases/",
      "Sonatype OSS Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots/"
    )
  )
)

// https://github.com/sbt/sbt/issues/3570
updateOptions := updateOptions.value.withGigahorse(false)

// artifact name will include scala version
crossPaths := true

// patch for error on 'early-semver' problems
ThisBuild / evictionErrorLevel := Level.Info

val `scala-3` = "3.3.4" // Scala 3 LTS version
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
  "-Wunused:all", // Enable unused warnings for Scala 3 (required for scalafix)
  "-Wconf:msg=Compiler synthesis of Manifest:s,cat=deprecation:s", // Suppress Manifest deprecation warnings
  "-Ykind-projector", // Scala 3 replacement for kind-projector plugin
  "-Xmax-inlines:64" // Increase inline depth limit for complex boopickle/circe derivations
)

def commonSettings(projectName: String): Seq[sbt.Def.Setting[_]] = Seq(
  name := projectName,
  organization := "com.chipprbots",
  scalaVersion := `scala-3`,
  // Override Scala library version to prevent SIP-51 errors with mixed Scala patch versions
  scalaModuleInfo ~= (_.map(_.withOverrideScalaVersion(true))),
  ThisBuild / scalafixDependencies ++= List(
    "com.github.liancheng" %% "organize-imports" % "0.6.0"
  ),
  // Scalanet snapshots are published to Sonatype after each build (now defined in inThisBuild resolvers).
  (Test / testOptions) += Tests
    .Argument(TestFrameworks.ScalaTest, "-l", "EthashMinerSpec"), // miner tests disabled by default,
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
  (Test / testOptions) += Tests.Argument("-oDG"),
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

  val dep = {
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
      Dependencies.scaffeine,
      Dependencies.scopt,
      Dependencies.testing
    ).flatten ++ malletDeps
  }

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
        BuildInfoKey.action("gitHeadCommit") { git.gitHeadCommit.?.value.flatten.map(_.take(7)).getOrElse("unknown") },
        BuildInfoKey.action("gitCurrentBranch") { 
          val branch = git.gitCurrentBranch.?.value.getOrElse("")
          if (branch != null && branch.nonEmpty) branch else "unknown"
        },
        BuildInfoKey.action("gitCurrentTags") { git.gitCurrentTags.?.value.getOrElse(Seq.empty).mkString(",") },
        BuildInfoKey.action("gitDescribedVersion") { git.gitDescribedVersion.?.value.flatten.getOrElse("unknown") },
        BuildInfoKey.action("gitUncommittedChanges") { git.gitUncommittedChanges.?.value.getOrElse(false) },
        (Compile / libraryDependencies)
      ),
      buildInfoPackage := "com.chipprbots.ethereum.utils",
      (Test / fork) := true,
      (Compile / buildInfoOptions) += BuildInfoOption.ToMap,
      // Temporarily exclude test files with MockFactory compilation issues (Scala 3 migration)
      // 2 tests fixed with abstract mock pattern: BranchResolutionSpec, ConsensusAdapterSpec
      // Remaining tests need different approaches (DaoForkTestSetup, TestSetup have existing self-types)
      //
      // DISABLED TESTS DOCUMENTATION:
      // Each excluded test is documented below with reason and remediation approach
      (Test / excludeFilter) := {
        val base = (Test / excludeFilter).value
        base ||
          // FIXED - using abstract mock members pattern:
          // FileFilter("BranchResolutionSpec.scala") ||
          // FileFilter("ConsensusAdapterSpec.scala") ||

          // DISABLED - Self-type conflicts with MockFactory (requires trait-based mocking refactor):
          FileFilter("BlockExecutionSpec.scala") || // Reason: DaoForkTestSetup has self-type requiring DAO fork configuration
                                                    // Remediation: Replace MockFactory with mockito-scala or refactor to composition
          FileFilter("JsonRpcHttpServerSpec.scala") || // Reason: TestSetup has self-type requiring HTTP server dependencies
                                                       // Remediation: Replace MockFactory with mockito-scala or abstract mocks

          // DISABLED - Complex actor mocking incompatible with Scala 3 MockFactory:
          FileFilter("ConsensusImplSpec.scala") || // Reason: MockFactory incompatible with Scala 3 for actor system mocking
                                                   // Remediation: Migrate to cats-effect TestControl or akka-testkit patterns
          FileFilter("FastSyncBranchResolverActorSpec.scala") || // Reason: Actor choreography mocking fails in Scala 3
                                                                 // Remediation: Use akka-testkit TestProbe or refactor to testable functions

          // DISABLED - Mining coordinator mocking issues (SlowTest alternatives exist):
          FileFilter("PoWMiningCoordinatorSpec.scala") || // Reason: Mining coordinator actor mocking incompatible with Scala 3
                                                          // Remediation: Migrate to integration tests or mockito-scala
          FileFilter("PoWMiningSpec.scala") || // Reason: Mining process mocking fails with Scala 3 MockFactory
                                               // Remediation: Use integration tests with test mining difficulty

          // DISABLED - Miner implementations (covered by integration tests, marked SlowTest):
          FileFilter("EthashMinerSpec.scala") || // Reason: Ethash PoW mining MockFactory incompatibility
                                                 // Remediation: Use integration tests or migrate to mockito-scala
          FileFilter("KeccakMinerSpec.scala") || // Reason: Keccak mining MockFactory incompatibility
                                                 // Remediation: Use integration tests or migrate to mockito-scala
          FileFilter("MockedMinerSpec.scala") || // Reason: Test miner MockFactory incompatibility
                                                 // Remediation: Migrate to mockito-scala for mock verification

          // DISABLED - ExtVM mocking issues (external VM integration):
          FileFilter("MessageHandlerSpec.scala") || // Reason: External VM message handling mocking fails in Scala 3
                                                    // Remediation: Replace MockFactory with mockito-scala

          // DISABLED - JSON-RPC service mocking incompatibilities:
          FileFilter("QaJRCSpec.scala") ||       // Reason: QA JSON-RPC controller MockFactory incompatibility
                                                 // Remediation: Migrate to mockito-scala
          FileFilter("EthProofServiceSpec.scala") || // Reason: Ethereum proof service mocking fails in Scala 3
                                                     // Remediation: Replace MockFactory with mockito-scala
          FileFilter("LegacyTransactionHistoryServiceSpec.scala") // Reason: Transaction history service MockFactory incompatibility
                                                                  // Remediation: Migrate to mockito-scala
      }
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
    .settings(inConfig(Benchmark)(Defaults.testSettings :+ (Test / parallelExecution := false)): _*)
    .settings(inConfig(Evm)(Defaults.testSettings :+ (Test / parallelExecution := false)): _*)
    .settings(inConfig(Rpc)(Defaults.testSettings :+ (Test / parallelExecution := false)): _*)
    .settings(
      // protobuf compilation
      // Into a subdirectory of src_managed to avoid it deleting other generated files; see https://github.com/sbt/sbt-buildinfo/issues/149
      (Compile / PB.targets) := Seq(
        scalapb.gen() -> (Compile / sourceManaged).value / "protobuf"
      ),
      // Use local protobuf override directory with corrected package namespace
      (Compile / PB.protoSources) := Seq(
        baseDirectory.value / "src" / "main" / "protobuf_override"
      ),
      // protobuf API version file is now provided in src/main/resources/extvm/VERSION
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
      // Assembly configuration
      (assembly / mainClass) := Some("com.chipprbots.ethereum.App"),
      (assembly / assemblyJarName) := s"fukuii-assembly-${version.value}.jar",
      (assembly / assemblyMergeStrategy) := {
        case PathList("META-INF", "MANIFEST.MF") => MergeStrategy.discard
        case PathList("META-INF", xs @ _*) if xs.lastOption.exists(_.endsWith(".SF")) => MergeStrategy.discard
        case PathList("META-INF", xs @ _*) if xs.lastOption.exists(_.endsWith(".DSA")) => MergeStrategy.discard
        case PathList("META-INF", xs @ _*) if xs.lastOption.exists(_.endsWith(".RSA")) => MergeStrategy.discard
        case PathList("META-INF", "io.netty.versions.properties") => MergeStrategy.first
        case PathList("META-INF", "native", xs @ _*) => MergeStrategy.first
        case PathList("META-INF", "native-image", xs @ _*) => MergeStrategy.first
        case PathList("META-INF", "versions", xs @ _*) => MergeStrategy.first
        case "module-info.class" => MergeStrategy.discard
        case "reference.conf" => MergeStrategy.concat
        case "application.conf" => MergeStrategy.concat
        case x if x.endsWith(".proto") => MergeStrategy.first
        case x if x.contains("pekko") => MergeStrategy.first
        case x if x.contains("akka") => MergeStrategy.first
        case _ => MergeStrategy.first
      }
    )

  if (!nixBuild)
    node
  else
    node.settings((Compile / PB.protocExecutable) := file("protoc"))

}

// Scoverage configuration
coverageEnabled := false // Disabled by default, enable with `sbt coverage`
coverageMinimumStmtTotal := 70
coverageFailOnMinimum := true
coverageHighlighting := true
coverageExcludedPackages := Seq(
  "com\\.chipprbots\\.ethereum\\.extvm\\.msg.*",  // Protobuf generated code
  "com\\.chipprbots\\.ethereum\\.utils\\.BuildInfo",  // BuildInfo generated code
  ".*\\.protobuf\\..*"  // All protobuf packages
).mkString(";")
coverageExcludedFiles := Seq(
  ".*/src_managed/.*",  // All managed sources
  ".*/target/.*/src_managed/.*"  // Target managed sources
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
// Runs fast unit tests, excludes integration, slow, and sync tests
// Sync tests are excluded because they involve complex actor choreography (ADR-017)
addCommandAlias(
  "testEssential",
  """; compile-all
    |; testOnly -- -l SlowTest -l IntegrationTest -l SyncTest
    |; rlp / test
    |; bytes / test
    |; crypto / test
    |""".stripMargin
)

// testStandard - Tier 2: Standard tests (< 30 minutes)
// Runs unit and integration tests, excludes benchmarks and comprehensive ethereum tests
addCommandAlias(
  "testStandard",
  """; compile-all
    |; testOnly -- -l BenchmarkTest -l EthereumTest
    |""".stripMargin
)

// testComprehensive - Tier 3: Comprehensive tests (< 3 hours)
// Runs all tests including ethereum/tests compliance suite
addCommandAlias(
  "testComprehensive",
  "testAll"
)

// Module-specific test commands
addCommandAlias("testCrypto", "testOnly -- -n CryptoTest")
addCommandAlias("testVM", "testOnly -- -n VMTest")
addCommandAlias("testNetwork", "testOnly -- -n NetworkTest")
addCommandAlias("testDatabase", "testOnly -- -n DatabaseTest")
addCommandAlias("testRLP", "testOnly -- -n RLPTest")
addCommandAlias("testMPT", "testOnly -- -n MPTTest")
addCommandAlias("testEthereum", "testOnly -- -n EthereumTest")

// Scapegoat configuration for Scala 3
(ThisBuild / scapegoatVersion) := "3.1.4"
scapegoatReports := Seq("xml", "html")
scapegoatConsoleOutput := false
scapegoatDisabledInspections := Seq("UnsafeTraversableMethods")
scapegoatIgnoredFiles := Seq(
  ".*/src_managed/.*",
  ".*/target/.*protobuf/.*",
  ".*/BuildInfo\\.scala"
)
