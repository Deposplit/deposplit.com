name := """Deposplit"""
organization := "com.deposplit"

version := "1.0-SNAPSHOT"

lazy val root = (project in file("."))
    .enablePlugins(PlayScala)
    .aggregate(hexagon)
    .dependsOn(hexagon)
lazy val hexagon = project

scalaVersion := "3.3.7"

libraryDependencies += guice
libraryDependencies += "org.scalatestplus.play" %% "scalatestplus-play" % "7.0.2" % Test

// Adds additional packages into Twirl
//TwirlKeys.templateImports += "com.deposplit.controllers._"

libraryDependencies += "org.webjars.npm" % "popperjs__core" % "2.11.8"
libraryDependencies += "org.webjars.npm" % "bootstrap" % "5.3.8"

// Adds additional packages into conf/routes
// play.sbt.routes.RoutesKeys.routesImport += "com.deposplit.binders._"
