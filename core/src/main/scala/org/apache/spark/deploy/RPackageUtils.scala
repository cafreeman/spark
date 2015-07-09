/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.deploy

import java.io.{FileOutputStream, PrintStream, File}
import java.net.URI
import java.util.jar.JarFile

import com.google.common.io.Files
import org.apache.spark.util.{RedirectThread, Utils}

import scala.collection.JavaConversions._

private[deploy] object RPackageUtils {

  /** The key in the MANIFEST.mf that we look for, in case a jar contains R code. */
  private final val hasRPackage = "Spark-HasRPackage"

  /** Base of the shell command used in order to install R packages. */
  private final val baseInstallCmd = Seq("R", "CMD", "INSTALL", "-l")

  /** R source code should exist under R/pkg in a jar. */
  private final val RJarEntries = "R/pkg"

  /** Documentation on how the R source file layout should be in the jar. */
  private[deploy] final val RJarDoc =
    s"""In order for Spark to build R packages that are parts of Spark Packages, there are a few
      |requirements. The R source code must be shipped in a jar, with additional Java/Scala
      |classes. The jar must be in the following format:
      |  1- The Manifest (META-INF/MANIFEST.mf) must contain the key-value: $hasRPackage: true
      |  2- The standard R package layout must be preserved under R/pkg/ inside the jar. More
      |  information on the standard R package layout can be found in:
      |  http://cran.r-project.org/doc/contrib/Leisch-CreatingPackages.pdf
      |  An example layout is given below. After running `jar tf $$JAR_FILE | sort`:
      |
      |META-INF/MANIFEST.MF
      |R/
      |R/pkg/
      |R/pkg/DESCRIPTION
      |R/pkg/NAMESPACE
      |R/pkg/R/
      |R/pkg/R/myRcode.R
      |org/
      |org/apache/
      |...
    """.stripMargin.trim

  /**
   * Checks the manifest of the Jar whether there is any R source code bundled with it.
   * Exposed for testing.
   */
  private[deploy] def checkManifestForR(jar: JarFile): Boolean = {
    val manifest = jar.getManifest.getMainAttributes
    manifest.getValue(hasRPackage) != null && manifest.getValue(hasRPackage).trim == "true"
  }

  /**
   * Runs the standard R package installation code to build the R package from source.
   * Multiple runs don't cause problems.
   */
  private def rPackageBuilder(dir: File, printStream: PrintStream, verbose: Boolean): Boolean = {
    val sparkHome = sys.env.get("SPARK_HOME").orElse(sys.props.get("spark.test.home")).orNull
    if (sparkHome == null) throw new IllegalArgumentException("SPARK_HOME not set!")
    val pathToSparkR = Seq(sparkHome, "R", "lib").mkString(File.separator)
    val pathToPkg = Seq(dir, "R", "pkg").mkString(File.separator)
    val installCmd = baseInstallCmd ++ Seq(pathToSparkR, pathToPkg)
    if (verbose) {
      printStream.println(s"Building R package with the command: $installCmd")
    }
    try {
      val builder = new ProcessBuilder(installCmd)
      builder.redirectErrorStream(true)
      val env = builder.environment()
      env.clear()
      val process = builder.start()
      new RedirectThread(process.getInputStream, printStream, "redirect R packaging").start()
      process.waitFor() == 0
    } catch {
      case e: Throwable =>
        printStream.println(e.getMessage + "\n" + e.getStackTrace)
        false
    }
  }

  /**
   * Extracts the files under /R in the jar to a temporary directory for building.
   */
  private def extractRFolder(jar: JarFile, printStream: PrintStream, verbose: Boolean): File = {
    val tempDir = Utils.createTempDir(null)
    val jarEntries = jar.entries()
    while (jarEntries.hasMoreElements) {
      val entry = jarEntries.nextElement()
      val entryRIndex = entry.getName.indexOf(RJarEntries)
      if (entryRIndex > -1) {
        val entryPath = entry.getName.substring(entryRIndex)
        if (entry.isDirectory) {
          val dir = new File(tempDir, entryPath)
          if (verbose) {
            printStream.println(s"Creating directory: $dir")
          }
          dir.mkdirs
        } else {
          val inStream = jar.getInputStream(entry)
          val outPath = new File(tempDir, entryPath)
          Files.createParentDirs(outPath)
          val outStream = new FileOutputStream(outPath)
          if (verbose) {
            printStream.println(s"Extracting $entry to $outPath")
          }
          Utils.copyStream(inStream, outStream, closeStreams = true)
        }
      }
    }
    tempDir
  }

  /**
   * Extracts the files under /R in the jar to a temporary directory for building.
   */
  private[deploy] def checkAndBuildRPackage(
      jars: String,
      printStream: PrintStream,
      verbose: Boolean): Unit = {
    jars.split(",").foreach { jarPath =>
      val file = new File(jarPath)
      if (file.exists()) {
        val jar = new JarFile(file)
        if (checkManifestForR(jar)) {
          printStream.println(s"$file contains R source code. Now installing package.")
          val rSource = extractRFolder(jar, printStream, verbose)
          try {
            if (!rPackageBuilder(rSource, printStream, verbose)) {
              printStream.println(s"ERROR: Failed to build R package in $file.")
              printStream.println(RJarDoc)
            }
          } finally {
            rSource.delete() // clean up
          }
        } else {
          if (verbose) {
            printStream.println(s"$file doesn't contain R source code, skipping...")
          }
        }
      } else {
        printStream.println(s"WARN: $file resolved as dependency, but not found.")
      }
    }
  }
}
