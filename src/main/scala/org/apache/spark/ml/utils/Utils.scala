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

package org.apache.spark.ml.utils

import java.io._
import java.lang.management.{LockInfo, ManagementFactory, MonitorInfo, ThreadInfo}
import java.math.{MathContext, RoundingMode}
import java.net._
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import java.util.{Locale, Properties, Random, UUID}
import java.util.concurrent._
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.collection.Map
import scala.collection.mutable.ArrayBuffer
import scala.io.Source
import scala.reflect.ClassTag
import scala.util.Try
import scala.util.control.{ControlThrowable, NonFatal}

/** CallSite represents a place in user code. It can have a short and a long form. */
case class CallSite(shortForm: String, longForm: String)

object CallSite {
  val SHORT_FORM = "callSite.short"
  val LONG_FORM = "callSite.long"
  val empty = CallSite("", "")
}

/**
 * Various utility methods used by Spark.
 */
object Utils {
  val random = new Random()

  /**
    * Define a default value for driver memory here since this value is referenced across the code
    * base and nearly all files already use Utils.scala
    */
  private val MAX_DIR_CREATION_ATTEMPTS: Int = 10
  @volatile private var localRootDirs: Array[String] = null

  /**
    * The performance overhead of creating and logging strings for wide schemas can be large. To
    * limit the impact, we bound the number of fields to include by default. This can be overridden
    * by setting the 'spark.debug.maxToStringFields' conf in SparkEnv.
    */
  val DEFAULT_MAX_TO_STRING_FIELDS = 25

  /** Whether we have warned about plan string truncation yet. */
  private val truncationWarningPrinted = new AtomicBoolean(false)


  /** Serialize an object using Java serialization */
  def serialize[T](o: T): Array[Byte] = {
    val bos = new ByteArrayOutputStream()
    val oos = new ObjectOutputStream(bos)
    oos.writeObject(o)
    oos.close()
    bos.toByteArray
  }

  /** Deserialize an object using Java serialization */
  def deserialize[T](bytes: Array[Byte]): T = {
    val bis = new ByteArrayInputStream(bytes)
    val ois = new ObjectInputStream(bis)
    ois.readObject.asInstanceOf[T]
  }

  /** Deserialize an object using Java serialization and the given ClassLoader */
  def deserialize[T](bytes: Array[Byte], loader: ClassLoader): T = {
    val bis = new ByteArrayInputStream(bytes)
    val ois = new ObjectInputStream(bis) {
      override def resolveClass(desc: ObjectStreamClass): Class[_] = {
        // scalastyle:off classforname
        Class.forName(desc.getName, false, loader)
        // scalastyle:on classforname
      }
    }
    ois.readObject.asInstanceOf[T]
  }

  def deserializeLongValue(bytes: Array[Byte]): Long = {
    // Note: we assume that we are given a Long value encoded in network (big-endian) byte order
    var result = bytes(7) & 0xFFL
    result = result + ((bytes(6) & 0xFFL) << 8)
    result = result + ((bytes(5) & 0xFFL) << 16)
    result = result + ((bytes(4) & 0xFFL) << 24)
    result = result + ((bytes(3) & 0xFFL) << 32)
    result = result + ((bytes(2) & 0xFFL) << 40)
    result = result + ((bytes(1) & 0xFFL) << 48)
    result + ((bytes(0) & 0xFFL) << 56)
  }

  /**
    * Get the ClassLoader which loaded Spark.
    */
  def getSparkClassLoader: ClassLoader = getClass.getClassLoader

  /**
    * Get the Context ClassLoader on this thread or, if not present, the ClassLoader that
    * loaded Spark.
    *
    * This should be used whenever passing a ClassLoader to Class.ForName or finding the currently
    * active loader when setting up ClassLoader delegation chains.
    */
  def getContextOrSparkClassLoader: ClassLoader =
    Option(Thread.currentThread().getContextClassLoader).getOrElse(getSparkClassLoader)

  /** Determines whether the provided class is loadable in the current thread. */
  def classIsLoadable(clazz: String): Boolean = {
    // scalastyle:off classforname
    Try {
      Class.forName(clazz, false, getContextOrSparkClassLoader)
    }.isSuccess
    // scalastyle:on classforname
  }

  // scalastyle:off classforname
  /** Preferred alternative to Class.forName(className) */
  def classForName(className: String): Class[_] = {
    Class.forName(className, true, getContextOrSparkClassLoader)
    // scalastyle:on classforname
  }

  /**
    * Primitive often used when writing [[java.nio.ByteBuffer]] to [[java.io.DataOutput]]
    */
  def writeByteBuffer(bb: ByteBuffer, out: DataOutput): Unit = {
    if (bb.hasArray) {
      out.write(bb.array(), bb.arrayOffset() + bb.position(), bb.remaining())
    } else {
      val originalPosition = bb.position()
      val bbval = new Array[Byte](bb.remaining())
      bb.get(bbval)
      out.write(bbval)
      bb.position(originalPosition)
    }
  }

  /**
    * Primitive often used when writing [[java.nio.ByteBuffer]] to [[java.io.OutputStream]]
    */
  def writeByteBuffer(bb: ByteBuffer, out: OutputStream): Unit = {
    if (bb.hasArray) {
      out.write(bb.array(), bb.arrayOffset() + bb.position(), bb.remaining())
    } else {
      val originalPosition = bb.position()
      val bbval = new Array[Byte](bb.remaining())
      bb.get(bbval)
      out.write(bbval)
      bb.position(originalPosition)
    }
  }

  /**
    * JDK equivalent of `chmod 700 file`.
    *
    * @param file the file whose permissions will be modified
    * @return true if the permissions were successfully changed, false otherwise.
    */
  def chmod700(file: File): Boolean = {
    file.setReadable(false, false) &&
      file.setReadable(true, true) &&
      file.setWritable(false, false) &&
      file.setWritable(true, true) &&
      file.setExecutable(false, false) &&
      file.setExecutable(true, true)
  }

  /**
    * Create a directory inside the given parent directory. The directory is guaranteed to be
    * newly created, and is not marked for automatic deletion.
    */
  def createDirectory(root: String, namePrefix: String = "spark"): File = {
    var attempts = 0
    val maxAttempts = MAX_DIR_CREATION_ATTEMPTS
    var dir: File = null
    while (dir == null) {
      attempts += 1
      if (attempts > maxAttempts) {
        throw new IOException("Failed to create a temp directory (under " + root + ") after " +
          maxAttempts + " attempts!")
      }
      try {
        dir = new File(root, namePrefix + "-" + UUID.randomUUID.toString)
        if (dir.exists() || !dir.mkdirs()) {
          dir = null
        }
      } catch {
        case e: SecurityException => dir = null;
      }
    }

    dir.getCanonicalFile
  }

  /**
    * A file name may contain some invalid URI characters, such as " ". This method will convert the
    * file name to a raw path accepted by `java.net.URI(String)`.
    *
    * Note: the file name must not contain "/" or "\"
    */
  def encodeFileNameToURIRawPath(fileName: String): String = {
    require(!fileName.contains("/") && !fileName.contains("\\"))
    // `file` and `localhost` are not used. Just to prevent URI from parsing `fileName` as
    // scheme or host. The prefix "/" is required because URI doesn't accept a relative path.
    // We should remove it after we get the raw path.
    new URI("file", null, "localhost", -1, "/" + fileName, null, null).getRawPath.substring(1)
  }

  /**
    * Get the file name from uri's raw path and decode it. If the raw path of uri ends with "/",
    * return the name before the last "/".
    */
  def decodeFileNameInURI(uri: URI): String = {
    val rawPath = uri.getRawPath
    val rawFileName = rawPath.split("/").last
    new URI("file:///" + rawFileName).getPath.substring(1)
  }


  private def copyRecursive(source: File, dest: File): Unit = {
    if (source.isDirectory) {
      if (!dest.mkdir()) {
        throw new IOException(s"Failed to create directory ${dest.getPath}")
      }
      val subfiles = source.listFiles()
      subfiles.foreach(f => copyRecursive(f, new File(dest, f.getName)))
    } else {
      Files.copy(source.toPath, dest.toPath)
    }
  }

  /**
    * Validate that a given URI is actually a valid URL as well.
    *
    * @param uri The URI to validate
    */
  @throws[MalformedURLException]("when the URI is an invalid URL")
  def validateURL(uri: URI): Unit = {
    Option(uri.getScheme).getOrElse("file") match {
      case "http" | "https" | "ftp" =>
        try {
          uri.toURL
        } catch {
          case e: MalformedURLException =>
            val ex = new MalformedURLException(s"URI (${uri.toString}) is not a valid URL.")
            ex.initCause(e)
            throw ex
        }
      case _ => // will not be turned into a URL anyway
    }
  }


  /** Used by unit tests. Do not call from other places. */
  private[spark] def clearLocalRootDirs(): Unit = {
    localRootDirs = null
  }

  /**
    * Shuffle the elements of a collection into a random order, returning the
    * result in a new collection. Unlike scala.util.Random.shuffle, this method
    * uses a local random number generator, avoiding inter-thread contention.
    */
  def randomize[T: ClassTag](seq: TraversableOnce[T]): Seq[T] = {
    randomizeInPlace(seq.toArray)
  }

  /**
    * Shuffle the elements of an array into a random order, modifying the
    * original array. Returns the original array.
    */
  def randomizeInPlace[T](arr: Array[T], rand: Random = new Random): Array[T] = {
    for (i <- (arr.length - 1) to 1 by -1) {
      val j = rand.nextInt(i + 1)
      val tmp = arr(j)
      arr(j) = arr(i)
      arr(i) = tmp
    }
    arr
  }

  /**
    * Get the local host's IP address in dotted-quad format (e.g. 1.2.3.4).
    * Note, this is typically not used from within core spark.
    */

  private var customHostname: Option[String] = sys.env.get("SPARK_LOCAL_HOSTNAME")

  /**
    * Allow setting a custom host name because when we run on Mesos we need to use the same
    * hostname it reports to the master.
    */
  def setCustomHostname(hostname: String) {
    // DEBUG code
    Utils.checkHost(hostname)
    customHostname = Some(hostname)
  }


  def checkHost(host: String, message: String = "") {
    assert(host.indexOf(':') == -1, message)
  }

  def checkHostPort(hostPort: String, message: String = "") {
    assert(hostPort.indexOf(':') != -1, message)
  }

  // Typically, this will be of order of number of nodes in cluster
  // If not, we should change it to LRUCache or something.
  private val hostPortParseResults = new ConcurrentHashMap[String, (String, Int)]()

  def parseHostPort(hostPort: String): (String, Int) = {
    // Check cache first.
    val cached = hostPortParseResults.get(hostPort)
    if (cached != null) {
      return cached
    }

    val indx: Int = hostPort.lastIndexOf(':')
    // This is potentially broken - when dealing with ipv6 addresses for example, sigh ...
    // but then hadoop does not support ipv6 right now.
    // For now, we assume that if port exists, then it is valid - not check if it is an int > 0
    if (-1 == indx) {
      val retval = (hostPort, 0)
      hostPortParseResults.put(hostPort, retval)
      return retval
    }

    val retval = (hostPort.substring(0, indx).trim(), hostPort.substring(indx + 1).trim().toInt)
    hostPortParseResults.putIfAbsent(hostPort, retval)
    hostPortParseResults.get(hostPort)
  }

  /**
    * Return the string to tell how long has passed in milliseconds.
    */
  def getUsedTimeMs(startTimeMs: Long): String = {
    " " + (System.currentTimeMillis - startTimeMs) + " ms"
  }

  private def listFilesSafely(file: File): Seq[File] = {
    if (file.exists()) {
      val files = file.listFiles()
      if (files == null) {
        throw new IOException("Failed to list files for dir: " + file)
      }
      files
    } else {
      List()
    }
  }


  /**
    * Check to see if file is a symbolic link.
    */
  def isSymlink(file: File): Boolean = {
    return Files.isSymbolicLink(Paths.get(file.toURI))
  }

  /**
    * Determines if a directory contains any files newer than cutoff seconds.
    *
    * @param dir    must be the path to a directory, or IllegalArgumentException is thrown
    * @param cutoff measured in seconds. Returns true if there are any files or directories in the
    *               given directory whose last modified time is later than this many seconds ago
    */
  def doesDirectoryContainAnyNewFiles(dir: File, cutoff: Long): Boolean = {
    if (!dir.isDirectory) {
      throw new IllegalArgumentException(s"$dir is not a directory!")
    }
    val filesAndDirs = dir.listFiles()
    val cutoffTimeInMillis = System.currentTimeMillis - (cutoff * 1000)

    filesAndDirs.exists(_.lastModified() > cutoffTimeInMillis) ||
      filesAndDirs.filter(_.isDirectory).exists(
        subdir => doesDirectoryContainAnyNewFiles(subdir, cutoff)
      )
  }

  /**
    * Convert a quantity in bytes to a human-readable string such as "4.0 MB".
    */
  def bytesToString(size: Long): String = bytesToString(BigInt(size))

  def bytesToString(size: BigInt): String = {
    val EB = 1L << 60
    val PB = 1L << 50
    val TB = 1L << 40
    val GB = 1L << 30
    val MB = 1L << 20
    val KB = 1L << 10

    if (size >= BigInt(1L << 11) * EB) {
      // The number is too large, show it in scientific notation.
      BigDecimal(size, new MathContext(3, RoundingMode.HALF_UP)).toString() + " B"
    } else {
      val (value, unit) = {
        if (size >= 2 * EB) {
          (BigDecimal(size) / EB, "EB")
        } else if (size >= 2 * PB) {
          (BigDecimal(size) / PB, "PB")
        } else if (size >= 2 * TB) {
          (BigDecimal(size) / TB, "TB")
        } else if (size >= 2 * GB) {
          (BigDecimal(size) / GB, "GB")
        } else if (size >= 2 * MB) {
          (BigDecimal(size) / MB, "MB")
        } else if (size >= 2 * KB) {
          (BigDecimal(size) / KB, "KB")
        } else {
          (BigDecimal(size), "B")
        }
      }
      "%.1f %s".formatLocal(Locale.US, value, unit)
    }
  }

  /**
    * Returns a human-readable string representing a duration such as "35ms"
    */
  def msDurationToString(ms: Long): String = {
    val second = 1000
    val minute = 60 * second
    val hour = 60 * minute

    ms match {
      case t if t < second =>
        "%d ms".format(t)
      case t if t < minute =>
        "%.1f s".format(t.toFloat / second)
      case t if t < hour =>
        "%.1f m".format(t.toFloat / minute)
      case t =>
        "%.2f h".format(t.toFloat / hour)
    }
  }

  /**
    * Convert a quantity in megabytes to a human-readable string such as "4.0 MB".
    */
  def megabytesToString(megabytes: Long): String = {
    bytesToString(megabytes * 1024L * 1024L)
  }


  /**
    * Return and start a daemon thread that processes the content of the input stream line by line.
    */
  def processStreamByLine(
                           threadName: String,
                           inputStream: InputStream,
                           processLine: String => Unit): Thread = {
    val t = new Thread(threadName) {
      override def run() {
        for (line <- Source.fromInputStream(inputStream).getLines()) {
          processLine(line)
        }
      }
    }
    t.setDaemon(true)
    t.start()
    t
  }


  /** Default filtering function for finding call sites using `getCallSite`. */
  private def sparkInternalExclusionFunction(className: String): Boolean = {
    // A regular expression to match classes of the internal Spark API's
    // that we want to skip when finding the call site of a method.
    val SPARK_CORE_CLASS_REGEX =
    """^org\.apache\.spark(\.api\.java)?(\.util)?(\.rdd)?(\.broadcast)?\.[A-Z]""".r
    val SPARK_SQL_CLASS_REGEX = """^org\.apache\.spark\.sql.*""".r
    val SCALA_CORE_CLASS_PREFIX = "scala"
    val isSparkClass = SPARK_CORE_CLASS_REGEX.findFirstIn(className).isDefined ||
      SPARK_SQL_CLASS_REGEX.findFirstIn(className).isDefined
    val isScalaClass = className.startsWith(SCALA_CORE_CLASS_PREFIX)
    // If the class is a Spark internal class or a Scala class, then exclude.
    isSparkClass || isScalaClass
  }

  /**
    * When called inside a class in the spark package, returns the name of the user code class
    * (outside the spark package) that called into Spark, as well as which Spark method they called.
    * This is used, for example, to tell users where in their code each RDD got created.
    *
    * @param skipClass Function that is used to exclude non-user-code classes.
    */
  def getCallSite(skipClass: String => Boolean = sparkInternalExclusionFunction): CallSite = {
    // Keep crawling up the stack trace until we find the first function not inside of the spark
    // package. We track the last (shallowest) contiguous Spark method. This might be an RDD
    // transformation, a SparkContext function (such as parallelize), or anything else that leads
    // to instantiation of an RDD. We also track the first (deepest) user method, file, and line.
    var lastSparkMethod = "<unknown>"
    var firstUserFile = "<unknown>"
    var firstUserLine = 0
    var insideSpark = true
    var callStack = new ArrayBuffer[String]() :+ "<unknown>"

    Thread.currentThread.getStackTrace().foreach { ste: StackTraceElement =>
      // When running under some profilers, the current stack trace might contain some bogus
      // frames. This is intended to ensure that we don't crash in these situations by
      // ignoring any frames that we can't examine.
      if (ste != null && ste.getMethodName != null
        && !ste.getMethodName.contains("getStackTrace")) {
        if (insideSpark) {
          if (skipClass(ste.getClassName)) {
            lastSparkMethod = if (ste.getMethodName == "<init>") {
              // Spark method is a constructor; get its class name
              ste.getClassName.substring(ste.getClassName.lastIndexOf('.') + 1)
            } else {
              ste.getMethodName
            }
            callStack(0) = ste.toString // Put last Spark method on top of the stack trace.
          } else {
            if (ste.getFileName != null) {
              firstUserFile = ste.getFileName
              if (ste.getLineNumber >= 0) {
                firstUserLine = ste.getLineNumber
              }
            }
            callStack += ste.toString
            insideSpark = false
          }
        } else {
          callStack += ste.toString
        }
      }
    }

    val callStackDepth = System.getProperty("spark.callstack.depth", "20").toInt
    val shortForm =
      if (firstUserFile == "HiveSessionImpl.java") {
        // To be more user friendly, show a nicer string for queries submitted from the JDBC
        // server.
        "Spark JDBC Server Query"
      } else {
        s"$lastSparkMethod at $firstUserFile:$firstUserLine"
      }
    val longForm = callStack.take(callStackDepth).mkString("\n")

    CallSite(shortForm, longForm)
  }

  private def isSpace(c: Char): Boolean = {
    " \t\r\n".indexOf(c) != -1
  }

  /**
    * Split a string of potentially quoted arguments from the command line the way that a shell
    * would do it to determine arguments to a command. For example, if the string is 'a "b c" d',
    * then it would be parsed as three arguments: 'a', 'b c' and 'd'.
    */
  def splitCommandString(s: String): Seq[String] = {
    val buf = new ArrayBuffer[String]
    var inWord = false
    var inSingleQuote = false
    var inDoubleQuote = false
    val curWord = new StringBuilder

    def endWord() {
      buf += curWord.toString
      curWord.clear()
    }

    var i = 0
    while (i < s.length) {
      val nextChar = s.charAt(i)
      if (inDoubleQuote) {
        if (nextChar == '"') {
          inDoubleQuote = false
        } else if (nextChar == '\\') {
          if (i < s.length - 1) {
            // Append the next character directly, because only " and \ may be escaped in
            // double quotes after the shell's own expansion
            curWord.append(s.charAt(i + 1))
            i += 1
          }
        } else {
          curWord.append(nextChar)
        }
      } else if (inSingleQuote) {
        if (nextChar == '\'') {
          inSingleQuote = false
        } else {
          curWord.append(nextChar)
        }
        // Backslashes are not treated specially in single quotes
      } else if (nextChar == '"') {
        inWord = true
        inDoubleQuote = true
      } else if (nextChar == '\'') {
        inWord = true
        inSingleQuote = true
      } else if (!isSpace(nextChar)) {
        curWord.append(nextChar)
        inWord = true
      } else if (inWord && isSpace(nextChar)) {
        endWord()
        inWord = false
      }
      i += 1
    }
    if (inWord || inDoubleQuote || inSingleQuote) {
      endWord()
    }
    buf
  }

  /* Calculates 'x' modulo 'mod', takes to consideration sign of x,
  * i.e. if 'x' is negative, than 'x' % 'mod' is negative too
  * so function return (x % mod) + mod in that case.
  */
  def nonNegativeMod(x: Int, mod: Int): Int = {
    val rawMod = x % mod
    rawMod + (if (rawMod < 0) mod else 0)
  }

  // Handles idiosyncrasies with hash (add more as required)
  // This method should be kept in sync with
  // org.apache.spark.network.util.JavaUtils#nonNegativeHash().
  def nonNegativeHash(obj: AnyRef): Int = {

    // Required ?
    if (obj eq null) return 0

    val hash = obj.hashCode
    // math.abs fails for Int.MinValue
    val hashAbs = if (Int.MinValue != hash) math.abs(hash) else 0

    // Nothing else to guard against ?
    hashAbs
  }

  /**
    * NaN-safe version of `java.lang.Double.compare()` which allows NaN values to be compared
    * according to semantics where NaN == NaN and NaN is greater than any non-NaN double.
    */
  def nanSafeCompareDoubles(x: Double, y: Double): Int = {
    val xIsNan: Boolean = java.lang.Double.isNaN(x)
    val yIsNan: Boolean = java.lang.Double.isNaN(y)
    if ((xIsNan && yIsNan) || (x == y)) 0
    else if (xIsNan) 1
    else if (yIsNan) -1
    else if (x > y) 1
    else -1
  }

  /**
    * NaN-safe version of `java.lang.Float.compare()` which allows NaN values to be compared
    * according to semantics where NaN == NaN and NaN is greater than any non-NaN float.
    */
  def nanSafeCompareFloats(x: Float, y: Float): Int = {
    val xIsNan: Boolean = java.lang.Float.isNaN(x)
    val yIsNan: Boolean = java.lang.Float.isNaN(y)
    if ((xIsNan && yIsNan) || (x == y)) 0
    else if (xIsNan) 1
    else if (yIsNan) -1
    else if (x > y) 1
    else -1
  }

  /**
    * Returns the system properties map that is thread-safe to iterator over. It gets the
    * properties which have been set explicitly, as well as those for which only a default value
    * has been defined.
    */
  def getSystemProperties: Map[String, String] = {
    System.getProperties.stringPropertyNames().asScala
      .map(key => (key, System.getProperty(key))).toMap
  }

  /**
    * Method executed for repeating a task for side effects.
    * Unlike a for comprehension, it permits JVM JIT optimization
    */
  def times(numIters: Int)(f: => Unit): Unit = {
    var i = 0
    while (i < numIters) {
      f
      i += 1
    }
  }

  /**
    * Timing method based on iterations that permit JVM JIT optimization.
    *
    * @param numIters number of iterations
    * @param f        function to be executed. If prepare is not None, the running time of each call to f
    *                 must be an order of magnitude longer than one millisecond for accurate timing.
    * @param prepare  function to be executed before each call to f. Its running time doesn't count.
    * @return the total time across all iterations (not counting preparation time)
    */
  def timeIt(numIters: Int)(f: => Unit, prepare: Option[() => Unit] = None): Long = {
    if (prepare.isEmpty) {
      val start = System.currentTimeMillis
      times(numIters)(f)
      System.currentTimeMillis - start
    } else {
      var i = 0
      var sum = 0L
      while (i < numIters) {
        prepare.get.apply()
        val start = System.currentTimeMillis
        f
        sum += System.currentTimeMillis - start
        i += 1
      }
      sum
    }
  }

  /**
    * Counts the number of elements of an iterator using a while loop rather than calling
    * [[scala.collection.Iterator#size]] because it uses a for loop, which is slightly slower
    * in the current version of Scala.
    */
  def getIteratorSize[T](iterator: Iterator[T]): Long = {
    var count = 0L
    while (iterator.hasNext) {
      count += 1L
      iterator.next()
    }
    count
  }

  /**
    * Generate a zipWithIndex iterator, avoid index value overflowing problem
    * in scala's zipWithIndex
    */
  def getIteratorZipWithIndex[T](iterator: Iterator[T], startIndex: Long): Iterator[(T, Long)] = {
    new Iterator[(T, Long)] {
      require(startIndex >= 0, "startIndex should be >= 0.")
      var index: Long = startIndex - 1L

      def hasNext: Boolean = iterator.hasNext

      def next(): (T, Long) = {
        index += 1L
        (iterator.next(), index)
      }
    }
  }

  /**
    * Creates a symlink.
    *
    * @param src absolute path to the source
    * @param dst relative path for the destination
    */
  def symlink(src: File, dst: File): Unit = {
    if (!src.isAbsolute()) {
      throw new IOException("Source must be absolute")
    }
    if (dst.isAbsolute()) {
      throw new IOException("Destination must be relative")
    }
    Files.createSymbolicLink(dst.toPath, src.toPath)
  }


  /** Return the class name of the given object, removing all dollar signs */
  def getFormattedClassName(obj: AnyRef): String = {
    obj.getClass.getSimpleName.replace("$", "")
  }


  /**
    * Pattern for matching a Windows drive, which contains only a single alphabet character.
    */
  val windowsDrive = "([a-zA-Z])".r

  /**
    * Indicates whether Spark is currently running unit tests.
    */
  def isTesting: Boolean = {
    sys.env.contains("SPARK_TESTING") || sys.props.contains("spark.testing")
  }

  /**
    * Strip the directory from a path name
    */
  def stripDirectory(path: String): String = {
    new File(path).getName
  }

  /**
    * Return the stderr of a process after waiting for the process to terminate.
    * If the process does not terminate within the specified timeout, return None.
    */
  def getStderr(process: Process, timeoutMs: Long): Option[String] = {
    val terminated = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
    if (terminated) {
      Some(Source.fromInputStream(process.getErrorStream).getLines().mkString("\n"))
    } else {
      None
    }
  }


  /** Returns true if the given exception was fatal. See docs for scala.util.control.NonFatal. */
  def isFatalError(e: Throwable): Boolean = {
    e match {
      case NonFatal(_) |
           _: InterruptedException |
           _: NotImplementedError |
           _: ControlThrowable |
           _: LinkageError =>
        false
      case _ =>
        true
    }
  }

  /**
    * Return a well-formed URI for the file described by a user input string.
    *
    * If the supplied path does not contain a scheme, or is a relative path, it will be
    * converted into an absolute path with a file:// scheme.
    */
  def resolveURI(path: String): URI = {
    try {
      val uri = new URI(path)
      if (uri.getScheme() != null) {
        return uri
      }
      // make sure to handle if the path has a fragment (applies to yarn
      // distributed cache)
      if (uri.getFragment() != null) {
        val absoluteURI = new File(uri.getPath()).getAbsoluteFile().toURI()
        return new URI(absoluteURI.getScheme(), absoluteURI.getHost(), absoluteURI.getPath(),
          uri.getFragment())
      }
    } catch {
      case e: URISyntaxException =>
    }
    new File(path).getAbsoluteFile().toURI()
  }

  /** Resolve a comma-separated list of paths. */
  def resolveURIs(paths: String): String = {
    if (paths == null || paths.trim.isEmpty) {
      ""
    } else {
      paths.split(",").filter(_.trim.nonEmpty).map { p => Utils.resolveURI(p) }.mkString(",")
    }
  }


  /** Return the path of the default Spark properties file. */
  def getDefaultPropertiesFile(env: Map[String, String] = sys.env): String = {
    env.get("SPARK_CONF_DIR")
      .orElse(env.get("SPARK_HOME").map { t => s"$t${File.separator}conf" })
      .map { t => new File(s"$t${File.separator}spark-defaults.conf") }
      .filter(_.isFile)
      .map(_.getAbsolutePath)
      .orNull
  }

  /**
    * Return a nice string representation of the exception. It will call "printStackTrace" to
    * recursively generate the stack trace including the exception and its causes.
    */
  def exceptionString(e: Throwable): String = {
    if (e == null) {
      ""
    } else {
      // Use e.printStackTrace here because e.getStackTrace doesn't include the cause
      val stringWriter = new StringWriter()
      e.printStackTrace(new PrintWriter(stringWriter))
      stringWriter.toString
    }
  }

  private implicit class Lock(lock: LockInfo) {
    def lockString: String = {
      lock match {
        case monitor: MonitorInfo =>
          s"Monitor(${lock.getClassName}@${lock.getIdentityHashCode}})"
        case _ =>
          s"Lock(${lock.getClassName}@${lock.getIdentityHashCode}})"
      }
    }
  }


  /**
    * Returns the user port to try when trying to bind a service. Handles wrapping and skipping
    * privileged ports.
    */
  def userPort(base: Int, offset: Int): Int = {
    (base + offset - 1024) % (65536 - 1024) + 1024
  }


  def invoke(
              clazz: Class[_],
              obj: AnyRef,
              methodName: String,
              args: (Class[_], AnyRef)*): AnyRef = {
    val (types, values) = args.unzip
    val method = clazz.getDeclaredMethod(methodName, types: _*)
    method.setAccessible(true)
    method.invoke(obj, values.toSeq: _*)
  }


  /**
    * Split the comma delimited string of master URLs into a list.
    * For instance, "spark://abc,def" becomes [spark://abc, spark://def].
    */
  def parseStandaloneMasterUrls(masterUrls: String): Array[String] = {
    masterUrls.stripPrefix("spark://").split(",").map("spark://" + _)
  }

  /** An identifier that backup masters use in their responses. */
  val BACKUP_STANDALONE_MASTER_PREFIX = "Current state is not alive"

  /** Return true if the response message is sent from a backup Master on standby. */
  def responseFromBackup(msg: String): Boolean = {
    msg.startsWith(BACKUP_STANDALONE_MASTER_PREFIX)
  }

  /**
    * Return whether the specified file is a parent directory of the child file.
    */
  @tailrec
  def isInDirectory(parent: File, child: File): Boolean = {
    if (child == null || parent == null) {
      return false
    }
    if (!child.exists() || !parent.exists() || !parent.isDirectory()) {
      return false
    }
    if (parent.equals(child)) {
      return true
    }
    isInDirectory(parent, child.getParentFile)
  }


  def tryWithResource[R <: Closeable, T](createResource: => R)(f: R => T): T = {
    val resource = createResource
    try f.apply(resource) finally resource.close()
  }

  /**
    * Returns a path of temporary file which is in the same directory with `path`.
    */
  def tempFileWith(path: File): File = {
    new File(path.getAbsolutePath + "." + UUID.randomUUID())
  }

  /**
    * Returns the name of this JVM process. This is OS dependent but typically (OSX, Linux, Windows),
    * this is formatted as PID@hostname.
    */
  def getProcessName(): String = {
    ManagementFactory.getRuntimeMXBean().getName()
  }

  /**
    * Unions two comma-separated lists of files and filters out empty strings.
    */
  def unionFileLists(leftList: Option[String], rightList: Option[String]): Set[String] = {
    var allFiles = Set[String]()
    leftList.foreach { value => allFiles ++= value.split(",") }
    rightList.foreach { value => allFiles ++= value.split(",") }
    allFiles.filter {
      _.nonEmpty
    }
  }
}


