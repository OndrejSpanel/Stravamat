import sbtappengine.Plugin.{AppengineKeys => gae}

name := "StravaLapAndSplit"

scalaVersion := "2.11.8"

libraryDependencies ++= Seq(
  //"org.scalatra" % "scalatra_2.10" % "2.0.5" withSources,
  //"com.samskivert" %% "highchair-datastore" % "0.0.5" withSources,
  "com.google.http-client" % "google-http-client-appengine" % "1.22.0",
  "com.google.http-client" % "google-http-client-jackson" % "1.22.0",
  "javax.servlet" % "servlet-api" % "2.5" % "provided",
  "org.mortbay.jetty" % "jetty" % "6.1.22" % "container",
  "joda-time" % "joda-time" % "2.9.4",
  "org.joda" % "joda-convert" % "1.8.1",
  "org.scalatra.scalate" %% "scalate-core" % "1.7.1",
  "com.fasterxml.jackson.core" % "jackson-core" % "2.8.2",
  "com.fasterxml.jackson.core" % "jackson-databind" % "2.8.2"
)

appengineSettings

(gae.onStartHooks in gae.devServer in Compile) += { () =>
  println("hello")
}

(gae.onStopHooks in gae.devServer in Compile) += { () =>
  println("bye")
}

//appengineDataNucleusSettings

//gae.persistenceApi in gae.enhance in Compile := "JDO"
