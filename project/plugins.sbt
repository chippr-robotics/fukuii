logLevel := sbt.Level.Warn

// Fix dependency conflict for scala-xml
ThisBuild / libraryDependencySchemes += "org.scala-lang.modules" %% "scala-xml" % "always"

addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.10.0")
addSbtPlugin("com.github.mwz" % "sbt-sonar" % "2.2.0")
addSbtPlugin("com.lightbend.sbt" % "sbt-javaagent" % "0.1.6")
addSbtPlugin("com.sksamuel.scapegoat" % "sbt-scapegoat" % "1.2.13")
addSbtPlugin("com.thoughtworks.sbt-api-mappings" % "sbt-api-mappings" % "3.0.0")
addSbtPlugin("com.timushev.sbt" % "sbt-updates" % "0.5.1")
addSbtPlugin("com.typesafe.sbt" % "sbt-git" % "1.0.0")
addSbtPlugin("com.typesafe.sbt" % "sbt-native-packager" % "1.7.5")
addSbtPlugin("io.kamon" % "sbt-kanela-runner" % "2.0.5")
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.5.2")
addSbtPlugin("org.scoverage" % "sbt-scoverage" % "2.0.10")
addSbtPlugin("com.geirsson" % "sbt-ci-release" % "1.5.6")
addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.13.0")
