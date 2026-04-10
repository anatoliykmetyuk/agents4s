val scala3Version = "3.8.3"

ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := scala3Version
ThisBuild / organization := "me.anatoliikmt"
ThisBuild / description :=
  "Scala 3 library for driving agent CLIs in tmux via a unified interface (agents4s)."
ThisBuild / licenses := List(
  "Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0"),
)

lazy val root = project
  .in(file("."))
  .settings(
    name := "agents4s",
    Test / fork := true,
    libraryDependencies ++= Seq(
      "com.lihaoyi" %% "os-lib" % "0.11.8",
      "org.scalatest" %% "scalatest" % "3.2.20" % Test,
    ),
    Test / testOptions ++= Seq(
      Tests.Argument(TestFrameworks.ScalaTest, "-oDF"),
    ),
  )
