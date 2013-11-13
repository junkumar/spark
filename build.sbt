resolvers += "Local Maven Repository" at "file://"+Path.userHome.absolutePath+"/.m2/repository"

libraryDependencies += "edu.berkeley.cs.amplab" %% "shark" % "0.9.0-SNAPSHOT"

libraryDependencies ++= Seq(
 "org.apache.hadoop" % "hadoop-client" % "1.0.4",
 "org.scalatest" %% "scalatest" % "1.9.1" % "test",
 "net.hydromatic" % "optiq-core" % "0.4.16-SNAPSHOT")

scalaVersion := "2.10.3"

initialCommands in console := """
import catalyst.analysis._
import catalyst.errors._
import catalyst.expressions._
import catalyst.frontend._
import catalyst.plans.logical._
import catalyst.plans.physical
import catalyst.rules._
import catalyst.types._
import catalyst.util._
lazy val testShark = new catalyst.util.TestShark
import testShark._"""