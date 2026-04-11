val scala3Version = "3.8.3"

/** Max concurrent tests per suite (ScalaTest `-P`, all suites). SCALATEST_PARALLELISM, else INTEGRATION_PARALLELISM, else 5. */
val scalaTestParallelism =
  sys.env.get("SCALATEST_PARALLELISM").orElse(sys.env.get("INTEGRATION_PARALLELISM")).getOrElse("5")

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
  .aggregate(core, pekko)
  .settings(
    name := "agents4s-root",
    publish / skip := true,
  )

lazy val core = project
  .in(file("agents4s-core"))
  .settings(
    name := "agents4s-core",
    Test / fork := true,
    libraryDependencies ++= Seq(
      "org.scalatest" %% "scalatest" % "3.2.20" % Test,
    ),
    Test / testOptions ++= Seq(
      Tests.Argument(TestFrameworks.ScalaTest, "-oDF", s"-P$scalaTestParallelism"),
    ),
  )

lazy val pekko = project
  .in(file("agents4s-pekko"))
  .dependsOn(core)
  .settings(
    name := "agents4s-pekko",
    Test / fork := true,
    libraryDependencies ++= Seq(
      "org.apache.pekko" %% "pekko-actor-typed" % "1.1.2",
      "org.apache.pekko" %% "pekko-actor-testkit-typed" % "1.1.2" % Test,
      "org.scalatest" %% "scalatest" % "3.2.20" % Test,
      "com.lihaoyi" %% "upickle" % "4.4.3",
      "com.lihaoyi" %% "upickle-jsonschema" % "4.4.3",
    ),
    Test / testOptions ++= Seq(
      Tests.Argument(TestFrameworks.ScalaTest, "-oDF", s"-P$scalaTestParallelism"),
    ),
  )
