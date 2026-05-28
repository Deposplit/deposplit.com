scalaVersion := "3.3.7"
// https://docs.scala-lang.org/scala3/reference/experimental/explicit-nulls.html
// scalacOptions += "-Yexplicit-nulls" 
// but waiting for Scala LTS with https://docs.scala-lang.org/scala3/reference/experimental/explicit-nulls.html#java-interoperability-and-flexible-types

libraryDependencies += "com.google.inject" % "guice" % "6.0.0"
libraryDependencies += "org.bouncycastle" % "bcprov-jdk18on" % "1.84"

// using https://scalameta.org/munit/ from the https://docs.scala-lang.org/toolkit/introduction.html#what-is-the-scala-toolkit
// alternative: https://github.com/com-lihaoyi/utest from the https://github.com/com-lihaoyi#the-lihaoyi-scala-platform-
libraryDependencies += "org.scalameta" %% "munit" % "1.3.0" % Test
