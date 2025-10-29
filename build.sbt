enablePlugins(JavaAppPackaging, SolidityPlugin, JavaAgent)

javaAgents += "io.kamon" % "kanela-agent" % "1.0.6"

import scala.sys.process.Process
import NativePackagerHelper._
import com.typesafe.sbt.SbtGit.GitKeys._

// Necessary for the nix build, please do not remove.
val nixBuild = sys.props.isDefinedAt("nix")

// Enable dev mode: disable certain flags, etc.
val fukuiiDev = sys.props.get("fukuiiDev").contains("true") || sys.env.get("FUKUII_DEV").contains("true")

// Scala 2.x specific optimizations
lazy val scala2OptimizationsForProd = Seq(
  "-opt:l:method", // method-local optimizations
  "-opt:l:inline", // inlining optimizations
  "-opt-inline-from:com.chipprbots.**" // inlining the project only
)

// Scala 3 has a different optimizer, these flags are not needed
lazy val scala3OptimizationsForProd = Seq.empty[String]

// Releasing. https://github.com/olafurpg/sbt-ci-release
inThisBuild(
  List(
    homepage := Some(url("https://github.com/chippr-robotics/chordodes_fukuii")),
    scmInfo := Some(
      ScmInfo(url("https://github.com/chippr-robotics/chordodes_fukuii"), "git@github.com:chippr-robotics/chordodes_fukuii.git")
    ),
    licenses := List("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")),
    developers := List()
  )
)

// https://github.com/sbt/sbt/issues/3570
updateOptions := updateOptions.value.withGigahorse(false)

// artifact name will include scala version
crossPaths := true

// patch for error on 'early-semver' problems
ThisBuild / evictionErrorLevel := Level.Info

val `scala-2.12` = "2.12.13"
val `scala-2.13` = "2.13.14" // Upgraded for json4s 4.0.x and circe 0.14.10 (SIP-51 requirement)
val `scala-3` = "3.3.4" // Scala 3 LTS version
val supportedScalaVersions = List(`scala-2.12`, `scala-2.13`)
val scala3SupportedVersions = List(`scala-2.13`, `scala-3`) // Cross-compilation target for Scala 3 migration

// Scala 2.x specific options
val scala2Options = Seq(
  "-Ywarn-unused",
  "-Xlint"
)

// Common options for both Scala 2 and Scala 3
val baseScalacOptions = Seq(
  "-unchecked",
  "-deprecation",
  "-feature",
  "-encoding",
  "utf-8"
)

// https://www.scala-lang.org/2021/01/12/configuring-and-suppressing-warnings.html
// cat={warning-name}:ws prints a summary with the number of warnings of the given type
// any:e turns all remaining warnings into errors
// Scala 2 only - Scala 3 uses different warning configuration
def fatalWarningsScala2 = Seq(if (sys.env.get("FUKUII_FULL_WARNS").contains("true")) {
  "-Wconf:any:w"
} else {
  "-Wconf:" ++ Seq(
    // Let's turn those gradually into errors:
    "cat=deprecation:ws",
    "cat=lint-package-object-classes:ws",
    "cat=unused:ws",
    "cat=lint-infer-any:ws",
    "cat=lint-byname-implicit:ws",
    "cat=other-match-analysis:ws",
    "any:e"
  ).mkString(",")
}) ++ Seq("-Ypatmat-exhaust-depth", "off")

// Scala 3 warning options
val scala3Options = Seq(
  "-Xfatal-warnings",
  "-Ykind-projector" // Scala 3 replacement for kind-projector plugin
)

def commonSettings(projectName: String): Seq[sbt.Def.Setting[_]] = Seq(
  name := projectName,
  organization := "com.chipprbots",
  scalaVersion := `scala-2.13`, // Primary version - Scala 3 available via cross-compilation
  // Override Scala library version to prevent SIP-51 errors with mixed Scala patch versions
  scalaModuleInfo ~= (_.map(_.withOverrideScalaVersion(true))),
  // NOTE: SemanticDB temporarily disabled for Scala 2.13.14 (not yet supported by semanticdb-scalac)
  // Will be re-enabled when semanticdb adds 2.13.14 support or after Scala 3 migration
  // semanticdbEnabled := true, // enable SemanticDB
  // semanticdbVersion := scalafixSemanticdb.revision, // use Scalafix compatible version
  ThisBuild / scalafixDependencies ++= List(
    "com.github.liancheng" %% "organize-imports" % "0.6.0"
  ),
  // Scalanet snapshots are published to Sonatype after each build.
  resolvers += "Sonatype OSS Snapshots".at("https://oss.sonatype.org/content/repositories/snapshots"),
  (Test / testOptions) += Tests
    .Argument(TestFrameworks.ScalaTest, "-l", "EthashMinerSpec"), // miner tests disabled by default,
  // Configure scalacOptions based on Scala version
  scalacOptions := {
    val base = baseScalacOptions
    val (versionSpecific, optimizations) = CrossVersion.partialVersion(scalaVersion.value) match {
      case Some((3, _)) => 
        (scala3Options, if (fukuiiDev) Seq.empty else scala3OptimizationsForProd)
      case Some((2, 13)) => 
        (scala2Options ++ fatalWarningsScala2, if (fukuiiDev) Seq.empty else scala2OptimizationsForProd)
      case Some((2, 12)) => 
        (scala2Options ++ fatalWarningsScala2, if (fukuiiDev) Seq.empty else scala2OptimizationsForProd)
      case _ => 
        (Seq.empty, Seq.empty)
    }
    base ++ versionSpecific ++ optimizations
  },
  (Compile / console / scalacOptions) ~= (_.filterNot(
    Set(
      "-Ywarn-unused-import",
      "-Xfatal-warnings"
    )
  )),
  (Compile / doc / scalacOptions) := baseScalacOptions,
  scalacOptions ~= (options => if (fukuiiDev) options.filterNot(_ == "-Xfatal-warnings") else options),
  Test / parallelExecution := true,
  (Test / testOptions) += Tests.Argument("-oDG"),
  // Only publish selected libraries.
  (publish / skip) := true
)

val publishSettings = Seq(
  publish / skip := false,
  crossScalaVersions := scala3SupportedVersions // Use Scala 2.13 and 3.3.4 for published libraries
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
      libraryDependencies ++=
        Dependencies.akka ++
          Dependencies.cats ++
          Dependencies.fs2 ++
          Dependencies.monix ++
          Dependencies.scodec ++
          Dependencies.netty ++
          Dependencies.crypto ++
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
      IntegrationTest / unmanagedSourceDirectories += baseDirectory.value / "it" / "src",
      Test / unmanagedSourceDirectories += baseDirectory.value / "ut" / "src",
      libraryDependencies ++=
        Dependencies.akka ++
          Dependencies.cats ++
          Dependencies.fs2 ++
          Dependencies.monix ++
          Dependencies.scodec ++
          Dependencies.netty ++
          Dependencies.crypto ++
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
        Dependencies.akkaUtil ++
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
        Dependencies.akkaUtil ++
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
        Dependencies.akkaUtil ++
          Dependencies.shapeless ++
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
      Dependencies.akka,
      Dependencies.akkaHttp,
      Dependencies.apacheCommons,
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
        gitHeadCommit,
        gitCurrentBranch,
        gitCurrentTags,
        gitDescribedVersion,
        gitUncommittedChanges,
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
          ++ org.scalafmt.sbt.ScalafmtPlugin.scalafmtConfigSettings :+ (Test / parallelExecution := false)
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
      (Compile / mainClass) := Some("com.chipprbots.ethereum.App"),
      (Compile / discoveredMainClasses) := Seq(),
      (Universal / mappings) ++= directory((Compile / resourceDirectory).value / "conf"),
      (Universal / mappings) += (Compile / resourceDirectory).value / "logback.xml" -> "conf/logback.xml",
      bashScriptExtraDefines += """addJava "-Dconfig.file=${app_home}/../conf/app.conf"""",
      bashScriptExtraDefines += """addJava "-Dlogback.configurationFile=${app_home}/../conf/logback.xml"""",
      batScriptExtraDefines += """call :add_java "-Dconfig.file=%APP_HOME%\conf\app.conf"""",
      batScriptExtraDefines += """call :add_java "-Dlogback.configurationFile=%APP_HOME%\conf\logback.xml""""
    )
    .settings(
      crossScalaVersions := scala3SupportedVersions // Enable cross-compilation for main node project
    )

  if (!nixBuild)
    node
  else
    //node.settings(PB.protocExecutable := file("protoc"))
    node.settings((Compile / PB.runProtoc) := ((args: Seq[String]) => Process("protoc", args) !))

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

// Scala 3 migration commands
addCommandAlias(
  "scala3Migrate",
  """; scala3-migrate
    |""".stripMargin
)

addCommandAlias(
  "scala3MigrateSyntax",
  """; scala3-migrate-syntax
    |""".stripMargin
)

addCommandAlias(
  "compileScala3",
  """; ++3.3.4
    |; compile-all
    |""".stripMargin
)

addCommandAlias(
  "testScala3",
  """; ++3.3.4
    |; testAll
    |""".stripMargin
)

// Scapegoat configuration
// Version 2.x/3.x for Scala 2.13.14+, version 1.x for older Scala 2.13 versions
(ThisBuild / scapegoatVersion) := (CrossVersion.partialVersion(scalaVersion.value) match {
  case Some((2, 13)) => "3.1.2"  // Scala 2.13.14+ supports Scapegoat 3.x
  case Some((3, _))  => "3.1.4"  // Scala 3 (when dependencies are ready)
  case _             => "1.4.16" // Fallback for older versions
})
scapegoatReports := Seq("xml", "html")
scapegoatConsoleOutput := false
scapegoatDisabledInspections := Seq("UnsafeTraversableMethods")
scapegoatIgnoredFiles := Seq(
  ".*/src_managed/.*",
  ".*/target/.*protobuf/.*",
  ".*/BuildInfo\\.scala"
)
