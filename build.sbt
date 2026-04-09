val scala3Version = "3.8.3"

ThisBuild / version := "0.1.0"
ThisBuild / scalaVersion := scala3Version

lazy val root = project
  .in(file("."))
  .settings(
    name := "cursor4s",
    Test / fork := true,
    libraryDependencies ++= Seq(
      "com.lihaoyi" %% "os-lib" % "0.11.8",
      "org.scalatest" %% "scalatest" % "3.2.20" % Test,
    ),
    Test / testOptions ++= Seq(
      Tests.Argument(TestFrameworks.ScalaTest, "-oDF"),
    ),
  )
