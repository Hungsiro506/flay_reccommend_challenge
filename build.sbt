
name := "flay_rcm_challenge"

val scalaBuidVersion = "2.11"
val jacksonVersion    = "2.8.4"
//val sparkVersion = "2.2.0"
val sparkVersion = "2.0.0"
val ficusVersion = "1.1.2"

val typeSafeVersion = "1.2.1"
val algebirdVersion = "0.12.0"


val sparkCore = "org.apache.spark" % s"spark-core_${scalaBuidVersion}" % sparkVersion % "provided" excludeAll ExclusionRule(organization = "javax.servlet")
val sparkStreaming = "org.apache.spark" % s"spark-streaming_${scalaBuidVersion}" % sparkVersion
val sparkMlib = "org.apache.spark" % s"spark-mllib_${scalaBuidVersion}" % sparkVersion
val sparkSQL = "org.apache.spark" % s"spark-sql_${scalaBuidVersion}" % sparkVersion

val jacksonCore = "com.fasterxml.jackson.core" % "jackson-core" % jacksonVersion
val jacksonDatabind = "com.fasterxml.jackson.core" % "jackson-databind" % jacksonVersion
val jacksonAnnotation = "com.fasterxml.jackson.core" % "jackson-annotations" % jacksonVersion
val jacksonModuleScala = "com.fasterxml.jackson.module" % s"jackson-module-scala_${scalaBuidVersion}" % jacksonVersion

val ficus = "net.ceedubs" % s"ficus_${scalaBuidVersion}" % ficusVersion
val algebirdCore = "com.twitter" % s"algebird-core_${scalaBuidVersion}" % algebirdVersion
val typesafeConfig  = "com.typesafe" % "config" % typeSafeVersion

val unit = "junit" % "junit" % "4.4" % "test"
val scalaTest = "org.scalatest" %% "scalatest" % "3.0.0" % "test"
val log4j = "log4j" % "log4j" % "1.2.17" % "provided"
val twitterBijection = "com.twitter" % s"bijection-avro_${scalaBuidVersion}" % "0.8.1"
val gson = "com.google.code.gson" % "gson" % "2.3.1"
val scalajHttp =  "org.scalaj" %% "scalaj-http" % "2.3.0"

lazy val commonSettings = Seq(
  organization := "com.ftel",
  version := "0.1.0-SNAPSHOT",
  scalaVersion := "2.11.7"
)

lazy val root = (project in file("."))
  .settings(
    commonSettings,
    name := "bigdata-radius",
    libraryDependencies ++= Seq(sparkCore,
      sparkStreaming,
      sparkMlib,
      sparkSQL,
      jacksonCore,
      jacksonAnnotation,
      jacksonDatabind,
      jacksonModuleScala,
      ficus,
      algebirdCore,
      typesafeConfig,
      unit,
      scalaTest,
      log4j,
      twitterBijection,
      gson,
      scalajHttp,
      "net.debasishg" % "redisclient_2.10" % "2.11"
    )
  )

assemblyMergeStrategy in assembly := {
  case PathList("META-INF", xs @ _*) => MergeStrategy.discard
  case x => MergeStrategy.first
}

assemblyExcludedJars in assembly := {
  val cp = (fullClasspath in assembly).value
  cp filter {_.data.getName == "scala-library.jar"}
  cp filter {_.data.getName == "junit-3.8.1.jar"}
}

resolvers += Resolver.mavenLocal