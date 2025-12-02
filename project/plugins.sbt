logLevel := sbt.Level.Warn

// Fix dependency conflict for scala-xml
ThisBuild / libraryDependencySchemes += "org.scala-lang.modules" %% "scala-xml" % "always"

// Override problematic transitive dependencies
ThisBuild / dependencyOverrides ++= Seq(
  "org.apache.httpcomponents" % "httpcore" % "4.4.16",
  "org.scala-lang.modules" %% "scala-xml" % "2.3.0"
)

// Add required plugin resolvers
resolvers += Resolver.sbtPluginRepo("releases")
resolvers += "Sonatype OSS Releases" at "https://oss.sonatype.org/content/repositories/releases/"
resolvers += "scalasbt" at "https://repo.scala-sbt.org/scalasbt/sbt-plugin-releases/"

addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.12.0")
addSbtPlugin("com.lightbend.sbt" % "sbt-javaagent" % "0.1.6")
addSbtPlugin("com.sksamuel.scapegoat" % "sbt-scapegoat" % "1.2.13")
addSbtPlugin("com.thoughtworks.sbt-api-mappings" % "sbt-api-mappings" % "3.0.2")
addSbtPlugin("com.timushev.sbt" % "sbt-updates" % "0.6.4")
addSbtPlugin("com.github.sbt" % "sbt-git" % "2.0.1")
addSbtPlugin("com.github.sbt" % "sbt-native-packager" % "1.10.4")
addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "2.3.0")
addSbtPlugin("io.kamon" % "sbt-kanela-runner" % "2.1.0")
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.5.2")
addSbtPlugin("org.scoverage" % "sbt-scoverage" % "2.2.2")
addSbtPlugin("com.github.sbt" % "sbt-ci-release" % "1.9.2")
addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.13.0")
