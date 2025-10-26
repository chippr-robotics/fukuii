// Configure library dependency schemes for the project build itself
// This resolves version conflicts in sbt plugins
ThisBuild / libraryDependencySchemes ++= Seq(
  "org.scala-lang.modules" %% "scala-xml" % "early-semver"
)
