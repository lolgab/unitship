package unitship

import java.net.{InetAddress, InetSocketAddress, ServerSocket, Socket}
import java.nio.file.*
import scala.jdk.CollectionConverters.*

/** Running processes. */
object Sys {
  final case class Result(code: Int, output: String) {
    def ok: Boolean = code == 0
  }

  private def builder(command: Seq[String], env: Map[String, String], cwd: Option[Path]): ProcessBuilder = {
    val pb = new ProcessBuilder(command.asJava)
    env.foreach((k, v) => pb.environment().put(k, v))
    cwd.foreach(d => pb.directory(d.toFile))
    pb
  }

  /** Runs the command and captures its output (stdout and stderr together). A missing program is a failure. */
  def run(
      command: Seq[String],
      env: Map[String, String] = Map.empty,
      cwd: Option[Path] = None,
      stdin: Option[Path] = None
  ): Result =
    try {
      val pb = builder(command, env, cwd).redirectErrorStream(true)
      stdin.foreach(f => pb.redirectInput(f.toFile))
      val process = pb.start()
      val output = new String(process.getInputStream.readAllBytes(), "UTF-8")
      Result(process.waitFor(), output.trim)
    } catch {
      case e: java.io.IOException => Result(127, s"cannot run ${command.head}: ${e.getMessage}")
    }

  /** Runs the command with the output of this process. Returns its exit code. */
  def inherit(
      command: Seq[String],
      env: Map[String, String] = Map.empty,
      cwd: Option[Path] = None,
      stdin: Option[Path] = None
  ): Int =
    try {
      val pb = builder(command, env, cwd).redirectOutput(ProcessBuilder.Redirect.INHERIT)
      pb.redirectError(ProcessBuilder.Redirect.INHERIT)
      stdin match {
        case Some(f) => pb.redirectInput(f.toFile)
        case None    => pb.redirectInput(ProcessBuilder.Redirect.INHERIT)
      }
      pb.start().waitFor()
    } catch {
      case e: java.io.IOException => throw new HostError(s"cannot run ${command.head}: ${e.getMessage}")
    }

  def exists(program: String): Boolean = run(Seq("sh", "-c", s"command -v ${Shell.quote(program)}")).ok

  def which(program: String): Option[String] =
    Some(run(Seq("sh", "-c", s"command -v ${Shell.quote(program)}"))).filter(_.ok).map(_.output.linesIterator.next())

  def isLinux: Boolean = System.getProperty("os.name", "").toLowerCase.contains("linux")

  /** True if nothing listens on `port` of the loopback interface. */
  def portIsFree(port: Int): Boolean =
    try {
      val s = new ServerSocket(port, 1, InetAddress.getByName("127.0.0.1"))
      s.close()
      true
    } catch { case _: java.io.IOException => false }

  def canConnect(port: Int, timeoutMillis: Int = 500): Boolean =
    try {
      val s = new Socket()
      try s.connect(new InetSocketAddress("127.0.0.1", port), timeoutMillis)
      finally s.close()
      true
    } catch { case _: java.io.IOException => false }

  def sleep(millis: Long): Unit = Thread.sleep(millis)
}

/** Files, with errors that are useful to the user. */
object Files2 {
  def read(path: Path): String = new String(Files.readAllBytes(path), "UTF-8")

  def write(path: Path, content: String): Unit = {
    Files.createDirectories(path.getParent)
    // Replaced atomically: a reader never sees half of a file.
    val tmp = Files.createTempFile(path.getParent, path.getFileName.toString, ".tmp")
    Files.write(tmp, content.getBytes("UTF-8"))
    Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
  }

  def readProps(path: Path): Map[String, String] =
    if (Files.exists(path)) Props.parse(read(path)) else Map.empty

  def writeProps(path: Path, props: Map[String, String]): Unit = write(path, Props.render(props))

  def list(dir: Path): Seq[Path] =
    if (!Files.isDirectory(dir)) Nil
    else {
      val stream = Files.list(dir)
      try stream.iterator().asScala.toSeq.sortBy(_.getFileName.toString)
      finally stream.close()
    }

  def deleteRecursively(path: Path): Unit =
    if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
      list(path).foreach(deleteRecursively)
      Files.delete(path)
    } else Files.deleteIfExists(path)

  def makeExecutable(path: Path): Unit = {
    import java.nio.file.attribute.PosixFilePermission.*
    Files.setPosixFilePermissions(path, java.util.EnumSet.of(lolgab_READ, lolgab_WRITE, lolgab_EXECUTE, GROUP_READ, GROUP_EXECUTE, OTHERS_READ, OTHERS_EXECUTE))
  }
}
