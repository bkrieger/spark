addSbtPlugin("com.typesafe.sbt" % "sbt-git" % "0.8.5")

// need to make changes to uptake sbt 1.0 support in "com.eed3si9n" % "sbt-assembly" % "1.14.5"
addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "0.11.2")

addSbtPlugin("com.typesafe.sbteclipse" % "sbteclipse-plugin" % "5.2.3")

addSbtPlugin("net.virtual-void" % "sbt-dependency-graph" % "0.9.0")

addSbtPlugin("org.scalastyle" %% "scalastyle-sbt-plugin" % "1.0.0")

addSbtPlugin("com.typesafe" % "sbt-mima-plugin" % "0.1.17")

// sbt 1.0.0 support: https://github.com/AlpineNow/junit_xml_listener/issues/6
addSbtPlugin("com.alpinenow" % "junit_xml_listener" % "0.5.1")

// need to make changes to uptake sbt 1.0 support in "com.eed3si9n" % "sbt-unidoc" % "0.4.1"
addSbtPlugin("com.eed3si9n" % "sbt-unidoc" % "0.3.3")

// need to make changes to uptake sbt 1.0 support in "com.cavorite" % "sbt-avro-1-7" % "1.1.2"
addSbtPlugin("com.cavorite" % "sbt-avro" % "0.3.2")

addSbtPlugin("io.spray" % "sbt-revolver" % "0.9.1")

libraryDependencies += "org.ow2.asm"  % "asm" % "5.1"

libraryDependencies += "org.ow2.asm"  % "asm-commons" % "5.1"

// sbt 1.0.0 support: https://github.com/ihji/sbt-antlr4/issues/14
addSbtPlugin("com.simplytyped" % "sbt-antlr4" % "0.7.11")

// Spark uses a custom fork of the sbt-pom-reader plugin which contains a patch to fix issues
// related to test-jar dependencies (https://github.com/sbt/sbt-pom-reader/pull/14). The source for
// this fork is published at https://github.com/JoshRosen/sbt-pom-reader/tree/v1.0.0-spark
// and corresponds to commit b160317fcb0b9d1009635a7c5aa05d0f3be61936 in that repository.
// In the long run, we should try to merge our patch upstream and switch to an upstream version of
// the plugin; this is tracked at SPARK-14401.

addSbtPlugin("org.spark-project" % "sbt-pom-reader" % "1.0.0-spark")
