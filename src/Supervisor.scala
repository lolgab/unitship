package unitship

import java.nio.file.*

/**
 * Keeps processes running. Processes are `Service`s: the edge FreeUnit and one per release of an app.
 *
 * `start` also makes the service start at boot where the supervisor can, so after a reboot the active releases and the
 * edge are back.
 */
trait Supervisor {
  def install(service: Service): Unit
  def start(name: String): Unit
  def stop(name: String): Unit
  def isActive(name: String): Boolean

  /** Stops the service and forgets it. */
  def remove(name: String): Unit

  /** Shows the logs of the service. Returns the exit code. */
  def logs(name: String, lines: Int, follow: Boolean): Int

  /** Memory and CPU used by the service and everything it started, where the supervisor can tell. */
  def stats(name: String): Stats
}

/** `memoryBytes` is the resident memory now, `cpuNanos` the CPU time used since the service started. */
final case class Stats(memoryBytes: Option[Long], cpuNanos: Option[Long])

/** systemd user units: the apps run as the unprivileged user that owns the host, with no root needed to deploy. */
final class SystemdSupervisor(home: Path) extends Supervisor {
  private val unitDir = Paths.get(System.getProperty("user.home"), ".config", "systemd", "user")

  // Over ssh without a login session, systemctl --user needs to find the user's manager.
  private lazy val env: Map[String, String] = {
    val uid = Sys.run(Seq("id", "-u")).output
    Map("XDG_RUNTIME_DIR" -> sys.env.getOrElse("XDG_RUNTIME_DIR", s"/run/user/$uid")) ++
      (if (sys.env.contains("DBUS_SESSION_BUS_ADDRESS")) Map.empty
       else Map("DBUS_SESSION_BUS_ADDRESS" -> s"unix:path=/run/user/$uid/bus"))
  }

  private def unitFile(name: String): Path = unitDir.resolve(s"$name.service")

  def ctl(args: String*): Sys.Result = Sys.run(Seq("systemctl", "--user") ++ args, env)

  private def ctlOrFail(args: String*): Unit = {
    val r = ctl(args*)
    if (!r.ok)
      throw new HostError(
        s"systemctl --user ${args.mkString(" ")} failed: ${r.output}\n" +
          "Is the user manager running? Run `unitship setup` and the root steps it prints (loginctl enable-linger)."
      )
  }

  def install(service: Service): Unit = {
    Files2.write(unitFile(service.name), SystemdUnit.render(service))
    ctlOrFail("daemon-reload")
  }

  def start(name: String): Unit = ctlOrFail("enable", "--now", name)

  def stop(name: String): Unit = ctl("disable", "--now", name)

  def isActive(name: String): Boolean = ctl("is-active", name).output.trim == "active"

  def remove(name: String): Unit = {
    stop(name)
    Files.deleteIfExists(unitFile(name))
    ctl("daemon-reload")
    ctl("reset-failed", name)
  }

  def logs(name: String, lines: Int, follow: Boolean): Int =
    Sys.inherit(
      Seq("journalctl", "--user", "-u", name, "-n", lines.toString, "--no-pager") ++ (if (follow) Seq("-f") else Nil),
      env
    )

  def stats(name: String): Stats = {
    val values = ctl("show", name, "-p", "MemoryCurrent", "-p", "CPUUsageNSec").output.linesIterator
      .flatMap(_.split("=", 2) match {
        case Array(k, v) => v.trim.toLongOption.map(k -> _)
        case _           => None
      })
      .toMap
    // systemd answers a huge number (2^64 - 1) when it doesn't know.
    def known(k: String) = values.get(k).filter(v => v >= 0 && v < Long.MaxValue / 2)
    Stats(known("MemoryCurrent"), known("CPUUsageNSec"))
  }

  /** The units of the daily maintenance: renewal of the certificates and rotation of the logs. */
  def installRenewal(bin: String): Unit = {
    Files2.write(unitDir.resolve("unitship-renew.service"), SystemdUnit.RenewService.format(bin))
    Files2.write(unitDir.resolve("unitship-renew.timer"), SystemdUnit.RenewTimer)
    Files2.write(unitDir.resolve("unitship-collect.service"), SystemdUnit.CollectService.format(bin))
    Files2.write(unitDir.resolve("unitship-collect.timer"), SystemdUnit.CollectTimer)
    ctlOrFail("daemon-reload")
    ctlOrFail("enable", "--now", "unitship-renew.timer")
    ctlOrFail("enable", "--now", "unitship-collect.timer")
  }

  val user: String = Sys.run(Seq("id", "-un")).output.trim

  def lingerEnabled: Boolean =
    Sys.run(Seq("loginctl", "show-user", user, "-p", "Linger")).output.trim == "Linger=yes"
}

/**
 * Plain background processes, with a pid file. For development and tests on machines without systemd (macOS): nothing
 * restarts a process that crashes and nothing starts at boot.
 */
final class LocalSupervisor(home: Path) extends Supervisor {
  private val runDir = home.resolve("run")
  private val Separator = '\u001f'.toString

  // The process must not inherit the descriptors of this one (a pipe of `git push`, which would then wait for the app
  // to exit): the child of a Scala Native ProcessBuilder keeps every descriptor that is not close-on-exec. Closing
  // 3 to 9 is what every shell can do.
  private val LaunchScript =
    "log=$1; shift; nohup \"$@\" >>\"$log\" 2>&1 </dev/null 3>&- 4>&- 5>&- 6>&- 7>&- 8>&- 9>&- & echo $!"

  private def spec(name: String) = runDir.resolve(s"$name.spec")
  private def pidFile(name: String) = runDir.resolve(s"$name.pid")

  private def pid(name: String): Option[Int] =
    if (Files.exists(pidFile(name))) Files2.read(pidFile(name)).trim.toIntOption else None

  def install(service: Service): Unit =
    Files2.writeProps(
      spec(service.name),
      Map(
        "command" -> service.command.mkString(Separator),
        "workDir" -> service.workDir,
        "envFile" -> service.envFile.getOrElse(""),
        "logFile" -> service.logFile
      )
    )

  def start(name: String): Unit = {
    if (isActive(name)) return
    val props = Files2.readProps(spec(name))
    if (props.isEmpty) throw new HostError(s"unknown service $name")
    val env = props.get("envFile").filter(_.nonEmpty).map(f => EnvFile.parse(Files2.read(Paths.get(f)))).getOrElse(Map.empty)
    val log = Paths.get(props("logFile"))
    Files.createDirectories(log.getParent)
    val command = props("command").split(Separator).toSeq
    val r = Sys.run(
      Seq("sh", "-c", LaunchScript, "sh", log.toString) ++ command,
      env,
      Some(Paths.get(props("workDir")))
    )
    r.output.trim.toIntOption match {
      case Some(p) if r.ok => Files2.write(pidFile(name), p.toString)
      case _               => throw new HostError(s"cannot start $name: ${r.output}")
    }
  }

  def isActive(name: String): Boolean = pid(name).exists(p => Sys.run(Seq("kill", "-0", p.toString)).ok)

  def stop(name: String): Unit = {
    pid(name).foreach { p =>
      Sys.run(Seq("kill", p.toString))
      var waited = 0
      while (isActive(name) && waited < 100) { Sys.sleep(100); waited += 1 }
      if (isActive(name)) Sys.run(Seq("kill", "-9", p.toString))
    }
    Files.deleteIfExists(pidFile(name))
  }

  def remove(name: String): Unit = {
    stop(name)
    Files.deleteIfExists(spec(name))
  }

  def stats(name: String): Stats =
    Stats(
      pid(name).flatMap(p => Sys.run(Seq("ps", "-o", "rss=", "-p", p.toString)).output.trim.toLongOption).map(_ * 1024),
      None
    )

  def logs(name: String, lines: Int, follow: Boolean): Int = {
    val file = Files2.readProps(spec(name)).get("logFile")
    file match {
      case Some(f) => Sys.inherit(Seq("tail", "-n", lines.toString) ++ (if (follow) Seq("-f") else Nil) :+ f)
      case None    => throw new HostError(s"unknown service $name")
    }
  }
}
