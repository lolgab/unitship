package unitship

import _root_.snunit.build.*
import java.nio.file.*

/** Settings of the host, in `host.conf`. */
final case class HostConfig(
    httpPort: Int,
    httpsPort: Int,
    baseDomain: Option[String],
    acmeEmail: Option[String],
    acmeServer: Option[String],
    supervisor: String,
    keep: Int,
    drainSeconds: Int,
    bin: String
) {
  def toProps: Map[String, String] =
    Map(
      "http_port" -> httpPort.toString,
      "https_port" -> httpsPort.toString,
      "supervisor" -> supervisor,
      "keep" -> keep.toString,
      "drain_seconds" -> drainSeconds.toString,
      "bin" -> bin
    ) ++ baseDomain.map("base_domain" -> _) ++ acmeEmail.map("acme_email" -> _) ++
      acmeServer.map("acme_server" -> _)
}

object HostConfig {
  def default: HostConfig =
    HostConfig(
      80,
      443,
      None,
      None,
      None,
      if (Sys.isLinux && Sys.exists("systemctl")) "systemd" else "local",
      5,
      5,
      "unitship"
    )

  def fromProps(p: Map[String, String]): HostConfig = {
    val d = default
    def int(key: String, fallback: Int) = p.get(key).flatMap(_.toIntOption).getOrElse(fallback)
    HostConfig(
      int("http_port", d.httpPort),
      int("https_port", d.httpsPort),
      p.get("base_domain").filter(_.nonEmpty),
      p.get("acme_email").filter(_.nonEmpty),
      p.get("acme_server").filter(_.nonEmpty),
      p.getOrElse("supervisor", d.supervisor),
      int("keep", d.keep),
      int("drain_seconds", d.drainSeconds),
      p.getOrElse("bin", d.bin)
    )
  }
}

final case class AppInfo(name: String, domains: Seq[String], active: Option[Int], previous: Option[Int])

/**
 * Everything the host does, in the directory `home`:
 *
 * {{{
 * host.conf                      settings
 * bin/unitd                      FreeUnit of the edge
 * edge/                          state, control socket and ACME webroot of the edge
 * certs/<domain>.pem             certificate chain and key, loaded in the edge
 * acme/                          what the ACME client (lego) keeps
 * git/<app>.git                  repositories for `git push` deploys
 * apps/<app>/app.conf            domains, active and previous release
 * apps/<app>/env                 the environment set with `config set`
 * apps/<app>/data/               persistent directory of the app (DATA_DIR)
 * apps/<app>/releases/<n>/       app (the executable), env and release.conf (its port)
 * }}}
 */
final class Host(val home: Path) {
  private val appsDir = home.resolve("apps")
  private val edgeDir = home.resolve("edge")
  private val certsDir = home.resolve("certs")
  private val acmeDir = home.resolve("acme")
  private val gitDir = home.resolve("git")
  private val binDir = home.resolve("bin")
  private val confFile = home.resolve("host.conf")
  private val controlSocket = edgeDir.resolve("control.sock")
  private val webroot = edgeDir.resolve("webroot")
  private val EdgeService = "unitship-edge"
  private val edgeLog = edgeDir.resolve("unit.log")
  private val accessLog = edgeDir.resolve("access.log")
  private val metricsDir = home.resolve("metrics")

  def config: HostConfig = HostConfig.fromProps(Files2.readProps(confFile))

  lazy val supervisor: Supervisor = config.supervisor match {
    case "systemd" => new SystemdSupervisor(home)
    case "local"   => new LocalSupervisor(home)
    case other     => throw new HostError(s"unknown supervisor '$other' in $confFile (expected systemd or local)")
  }

  private def say(message: String): Unit = println(s"-----> $message")

  private def requireSetup(): Unit =
    if (!Files.exists(confFile)) throw new HostError(s"$home is not set up: run `unitship setup` first")

  // ---- setup

  def setup(
      httpPort: Option[Int],
      httpsPort: Option[Int],
      baseDomain: Option[String],
      email: Option[String],
      acmeServer: Option[String],
      supervisorKind: Option[String],
      bin: Option[String]
  ): Unit = {
    val current = if (Files.exists(confFile)) config else HostConfig.default
    val updated = current.copy(
      httpPort = httpPort.getOrElse(current.httpPort),
      httpsPort = httpsPort.getOrElse(current.httpsPort),
      baseDomain = baseDomain.map(Names.domain).orElse(current.baseDomain),
      acmeEmail = email.orElse(current.acmeEmail),
      acmeServer = acmeServer.orElse(current.acmeServer),
      supervisor = supervisorKind.getOrElse(current.supervisor),
      bin = bin.orElse(Sys.which("unitship")).getOrElse(current.bin)
    )
    if (controlSocket.toString.length > 100)
      throw new HostError(s"the path of the control socket is too long (${controlSocket.toString.length} > 100): use a shorter --home")
    List(appsDir, edgeDir.resolve("state"), edgeDir.resolve("tmp"), webroot, certsDir, acmeDir, gitDir, binDir, metricsDir)
      .foreach(Files.createDirectories(_))
    Files2.writeProps(confFile, updated.toProps)

    val unitd = try SNUnitBuild.freeUnitDir(SNUnitBuild.hostPlatform).resolve("unitd")
    catch { case e: SNUnitBuildException => throw new HostError(e.getMessage) }
    Files.copy(unitd, binDir.resolve("unitd"), StandardCopyOption.REPLACE_EXISTING)
    Files2.makeExecutable(binDir.resolve("unitd"))

    supervisor match {
      case s: SystemdSupervisor => s.installRenewal(updated.bin)
      case _                    => ()
    }
    syncEdge()
    say(s"Host ready in $home (edge on :${updated.httpPort}/:${updated.httpsPort}, supervisor ${updated.supervisor})")
    prerequisites(updated).foreach(w => System.err.println(s"warning: $w"))
  }

  /** What is missing for the host to work, that only root can fix. */
  private def prerequisites(c: HostConfig): Seq[String] = {
    val warnings = Seq.newBuilder[String]
    if (!Sys.exists("curl")) warnings += "curl is not installed: the host uses it to talk to FreeUnit"
    if (!Sys.exists("lego"))
      warnings += "lego is not installed: needed to get TLS certificates (https://go-acme.github.io/lego/), or use `tls import`"
    supervisor match {
      case s: SystemdSupervisor =>
        if (!s.lingerEnabled)
          warnings += s"apps stop when you log out: as root run `loginctl enable-linger ${s.user}`"
      case _ => ()
    }
    val lowest = c.httpPort.min(c.httpsPort)
    val unprivileged = Option(Paths.get("/proc/sys/net/ipv4/ip_unprivileged_port_start"))
      .filter(Files.exists(_))
      .flatMap(p => Files2.read(p).trim.toIntOption)
    if (lowest < 1024 && unprivileged.exists(_ > lowest))
      warnings += s"this user cannot listen on port $lowest: as root run `sysctl -w net.ipv4.ip_unprivileged_port_start=$lowest` " +
        s"(and put net.ipv4.ip_unprivileged_port_start=$lowest in /etc/sysctl.d/50-unitship.conf)"
    warnings.result()
  }

  // ---- apps

  private def appDir(app: String) = appsDir.resolve(app)
  private def releasesDir(app: String) = appDir(app).resolve("releases")
  private def releaseDir(app: String, release: Int) = releasesDir(app).resolve(release.toString)
  private def serviceName(app: String, release: Int) = s"unitship-$app-$release"

  private def requireApp(app: String): Unit = {
    requireSetup()
    if (!Files.isDirectory(appDir(app))) throw new HostError(s"unknown app '$app': create it with `unitship create $app`")
  }

  def appNames: Seq[String] = Files2.list(appsDir).filter(Files.isDirectory(_)).map(_.getFileName.toString)

  def info(app: String): AppInfo = {
    val p = Files2.readProps(appDir(app).resolve("app.conf"))
    AppInfo(
      app,
      p.get("domains").toSeq.flatMap(_.split(',')).filter(_.nonEmpty),
      p.get("active").flatMap(_.toIntOption),
      p.get("previous").flatMap(_.toIntOption)
    )
  }

  private def save(i: AppInfo): Unit =
    Files2.writeProps(
      appDir(i.name).resolve("app.conf"),
      Map("domains" -> i.domains.mkString(",")) ++ i.active.map("active" -> _.toString) ++ i.previous.map("previous" -> _.toString)
    )

  private def releases(app: String): Seq[Int] =
    Files2.list(releasesDir(app)).flatMap(p => p.getFileName.toString.toIntOption).sorted

  private def releasePort(app: String, release: Int): Option[Int] =
    Files2.readProps(releaseDir(app, release).resolve("release.conf")).get("port").flatMap(_.toIntOption)

  private def checkDomainFree(app: String, domain: String): Unit =
    appNames.filter(_ != app).find(a => info(a).domains.contains(domain)).foreach { other =>
      throw new HostError(s"$domain already belongs to the app '$other'")
    }

  def create(app: String): Unit = {
    requireSetup()
    Names.app(app)
    if (Files.exists(appDir(app))) throw new HostError(s"the app '$app' already exists")
    val domain = config.baseDomain.map(b => Names.domain(s"$app.$b"))
    domain.foreach(checkDomainFree(app, _))
    Files.createDirectories(releasesDir(app))
    Files.createDirectories(appDir(app).resolve("data"))
    save(AppInfo(app, domain.toSeq, None, None))
    event(app, "create", None, "App created")
    syncEdge()
    say(s"Created app $app" + domain.map(d => s" (http://$d)").getOrElse(""))
    println(s"Deploy it with: unitship deploy $app <executable>")
  }

  def destroy(app: String): Unit = withLock(app) {
    requireApp(app)
    releases(app).foreach(r => supervisor.remove(serviceName(app, r)))
    Files2.deleteRecursively(appDir(app))
    Files2.deleteRecursively(gitDir.resolve(s"$app.git"))
    Files2.list(metricsDir).filter(_.getFileName.toString.startsWith(s"$app.")).foreach(Files.deleteIfExists(_))
    syncEdge()
    say(s"Destroyed app $app, its releases and its data")
  }

  private def withLock[A](app: String)(body: => A): A = {
    val lock = appDir(app).resolve(".lock")
    try Files.createFile(lock)
    catch {
      case _: FileAlreadyExistsException =>
        throw new HostError(s"another operation is running on '$app' (if it is not, delete $lock)")
      case _: NoSuchFileException => throw new HostError(s"unknown app '$app'")
    }
    try body
    finally Files.deleteIfExists(lock)
  }

  // ---- environment

  private def envFile(app: String) = appDir(app).resolve("env")

  def env(app: String): Map[String, String] = {
    requireApp(app)
    if (Files.exists(envFile(app))) EnvFile.parse(Files2.read(envFile(app))) else Map.empty
  }

  /** Changes the environment, then runs the active release again with it unless `restart` is false. */
  def changeEnv(app: String, set: Map[String, String], unset: Seq[String], restart: Boolean): Unit = {
    val current = env(app)
    (set.keys ++ unset).foreach { k =>
      if (!EnvFile.validKey(k)) throw new HostError(s"invalid variable name '$k'")
      if (Names.Reserved(k)) throw new HostError(s"$k is set by unitship and cannot be changed")
    }
    val updated = current -- unset ++ set
    withLock(app) {
      Files2.write(envFile(app), EnvFile.render(updated))
      event(
        app,
        "config",
        None,
        Seq(
          Some(set.keys.toSeq.sorted).filter(_.nonEmpty).map(k => s"Set ${k.mkString(", ")}"),
          Some(unset.sorted).filter(_.nonEmpty).map(k => s"Unset ${k.mkString(", ")}")
        ).flatten.mkString("; ")
      )
      if (restart && info(app).active.nonEmpty) {
        say("Applying the new configuration with a new release")
        redeployActive(app)
      }
    }
  }

  private def releaseEnv(app: String, release: Int, port: Int): Map[String, String] =
    (if (Files.exists(envFile(app))) EnvFile.parse(Files2.read(envFile(app))) else Map.empty) ++ Map(
      "PORT" -> port.toString,
      "BIND_ADDRESS" -> "127.0.0.1",
      "DATA_DIR" -> appDir(app).resolve("data").toString,
      "UNITSHIP_APP" -> app,
      "UNITSHIP_RELEASE" -> release.toString,
      // So that an app that manages the host (the panel) finds it.
      "UNITSHIP_HOME" -> home.toString,
      "UNITSHIP_BIN" -> config.bin
    )

  // ---- releases

  /** Installs `source` (an executable) as a new release and switches to it. */
  def deploy(app: String, source: Path): Unit = withLock(app) {
    requireApp(app)
    if (!Files.isRegularFile(source) || Files.size(source) == 0) throw new HostError(s"$source is not an executable")
    val release = releases(app).maxOption.getOrElse(0) + 1
    val dir = releaseDir(app, release)
    Files.createDirectories(dir)
    Files.move(source, dir.resolve("app"), StandardCopyOption.REPLACE_EXISTING)
    Files2.makeExecutable(dir.resolve("app"))
    say(s"Deploying $app, release $release")
    activate(app, release, discardOnFailure = true, kind = "deploy")
  }

  private def redeployActive(app: String): Unit =
    info(app).active.foreach { active =>
      val release = releases(app).max + 1
      Files.createDirectories(releaseDir(app, release))
      Files.copy(releaseDir(app, active).resolve("app"), releaseDir(app, release).resolve("app"))
      Files2.makeExecutable(releaseDir(app, release).resolve("app"))
      activate(app, release, discardOnFailure = true, kind = "config")
    }

  def rollback(app: String, requested: Option[Int]): Unit = withLock(app) {
    requireApp(app)
    val before = info(app)
    val target = Releases.rollbackTarget(releases(app), before.active, requested).fold(m => throw new HostError(m), identity)
    say(s"Rolling $app back to release $target")
    activate(app, target, discardOnFailure = false, kind = "rollback")
  }

  /** Starts `release` on a new port, waits until it is healthy, points the edge to it and stops the previous one. */
  private def activate(app: String, release: Int, discardOnFailure: Boolean, kind: String): Unit = {
    val c = config
    val before = info(app)
    val dir = releaseDir(app, release)
    val used = appNames.flatMap(a => releases(a).flatMap(releasePort(a, _))).toSet
    val port = Ports
      .allocate(used, Sys.portIsFree, start = used.maxOption.map(_ + 1).getOrElse(20000))
      .getOrElse(throw new HostError("no free port left in 20000-29999"))
    val service = Service(
      serviceName(app, release),
      s"unitship app $app, release $release",
      Seq(dir.resolve("app").toString),
      dir.toString,
      Some(dir.resolve("env").toString),
      appDir(app).resolve("logs").resolve(s"$release.log").toString
    )
    Files2.writeProps(dir.resolve("release.conf"), Map("port" -> port.toString))
    Files2.write(dir.resolve("env"), EnvFile.render(releaseEnv(app, release, port)))
    supervisor.install(service)
    supervisor.start(service.name)

    val userEnv = if (Files.exists(envFile(app))) EnvFile.parse(Files2.read(envFile(app))) else Map.empty[String, String]
    val path = userEnv.get("UNITSHIP_HEALTHCHECK_PATH").filter(_.nonEmpty)
    val timeout = userEnv.get("UNITSHIP_HEALTHCHECK_TIMEOUT").flatMap(_.toIntOption).getOrElse(30)
    say(s"Waiting for release $release on port $port" + path.map(p => s" (GET $p)").getOrElse(""))
    if (!waitHealthy(service.name, port, path, timeout)) {
      val crashed = !supervisor.isActive(service.name)
      System.err.println(s"release $release " + (if (crashed) "exited" else s"did not answer within ${timeout}s") + ", last logs:")
      supervisor.logs(service.name, 20, follow = false)
      supervisor.remove(service.name)
      if (discardOnFailure) Files2.deleteRecursively(dir)
      event(app, s"${kind}_failed", Some(release), s"Release $release " + (if (crashed) "exited" else s"did not answer within ${timeout}s"))
      throw new HostError(
        s"release $release failed" + before.active.map(a => s"; release $a is still serving").getOrElse("") +
          s"\nThe app must listen on 127.0.0.1:$$PORT (see README.md)."
      )
    }

    save(before.copy(active = Some(release), previous = before.active.filter(_ != release)))
    syncEdge()
    event(
      app,
      kind,
      Some(release),
      kind match {
        case "rollback" => s"Rolled back to release $release"
        case "config"   => s"Release $release is live with a new configuration"
        case _          => s"Release $release is live"
      }
    )
    say(s"Release $release is live")

    before.active.filter(_ != release).foreach { old =>
      say(s"Draining release $old for ${c.drainSeconds}s")
      Sys.sleep(c.drainSeconds * 1000L)
      supervisor.remove(serviceName(app, old))
    }
    val now = info(app)
    Releases.toPrune(releases(app), now.active, now.previous, c.keep).foreach { r =>
      supervisor.remove(serviceName(app, r))
      Files2.deleteRecursively(releaseDir(app, r))
    }
  }

  private def waitHealthy(service: String, port: Int, path: Option[String], timeoutSeconds: Int): Boolean = {
    val deadline = System.currentTimeMillis() + timeoutSeconds * 1000L
    var ok = false
    while (!ok && System.currentTimeMillis() < deadline && supervisor.isActive(service)) {
      ok = path match {
        case None => Sys.canConnect(port)
        case Some(p) =>
          Sys
            .run(Seq("curl", "-s", "-o", "/dev/null", "-m", "3", "-w", "%{http_code}", s"http://127.0.0.1:$port$p"))
            .output
            .toIntOption
            .exists(code => code > 0 && code < 500)
      }
      if (!ok) Sys.sleep(300)
    }
    // A process that answers and dies right away is not healthy.
    ok && { Sys.sleep(300); supervisor.isActive(service) }
  }

  def releaseList(app: String): Unit = {
    requireApp(app)
    val i = info(app)
    releases(app).foreach { r =>
      val mark = if (i.active.contains(r)) "active" else if (i.previous.contains(r)) "previous" else ""
      println(f"$r%5d  ${releasePort(app, r).map(p => s":$p").getOrElse("")}%-7s $mark")
    }
  }

  private def activeService(app: String): String = {
    requireApp(app)
    serviceName(app, info(app).active.getOrElse(throw new HostError(s"'$app' has no active release")))
  }

  /** Runs the executable of the active release with `args` and the environment of the app, for one-off tasks. */
  def exec(app: String, args: Seq[String]): Int = {
    requireApp(app)
    val release = info(app).active.getOrElse(throw new HostError(s"'$app' has no active release"))
    val dir = releaseDir(app, release)
    Sys.inherit(dir.resolve("app").toString +: args, releaseEnv(app, release, releasePort(app, release).getOrElse(0)), Some(dir))
  }

  def start(app: String): Unit = { supervisor.start(activeService(app)); event(app, "start", None, "Started"); say(s"Started $app") }
  def stop(app: String): Unit = { supervisor.stop(activeService(app)); event(app, "stop", None, "Stopped"); say(s"Stopped $app") }
  def restart(app: String): Unit = {
    val s = activeService(app)
    supervisor.stop(s)
    supervisor.start(s)
    event(app, "restart", None, "Restarted")
    say(s"Restarted $app")
  }

  private def edgeLogs(lines: Int, follow: Boolean): Int =
    Sys.inherit(Seq("tail", "-n", lines.toString) ++ (if (follow) Seq("-f") else Nil) :+ edgeLog.toString)

  def logs(app: Option[String], lines: Int, follow: Boolean): Int =
    app match {
      case Some(a) => supervisor.logs(activeService(a), lines, follow)
      case None    => edgeLogs(lines, follow)
    }

  def ps(): Unit = {
    requireSetup()
    val certs = certificateDomains
    println(f"${"APP"}%-20s ${"RELEASE"}%-8s ${"PORT"}%-6s ${"STATE"}%-8s DOMAINS")
    appNames.foreach { a =>
      val i = info(a)
      val state = i.active.map(r => if (supervisor.isActive(serviceName(a, r))) "running" else "stopped").getOrElse("-")
      val port = i.active.flatMap(releasePort(a, _)).map(_.toString).getOrElse("-")
      val domains = i.domains.map(d => if (certs(d)) s"https://$d" else s"http://$d").mkString(" ")
      println(f"$a%-20s ${i.active.map(_.toString).getOrElse("-")}%-8s $port%-6s $state%-8s $domains")
    }
    println(s"edge: ${if (supervisor.isActive(EdgeService)) "running" else "stopped"}")
  }

  // ---- domains

  def addDomains(app: String, domains: Seq[String]): Unit = withLock(app) {
    requireApp(app)
    val valid = domains.map(Names.domain)
    valid.foreach(checkDomainFree(app, _))
    val i = info(app)
    save(i.copy(domains = (i.domains ++ valid).distinct))
    event(app, "domain", None, s"Added ${valid.mkString(", ")}")
    syncEdge()
    say(s"$app is served on ${valid.mkString(", ")}")
  }

  def removeDomains(app: String, domains: Seq[String]): Unit = withLock(app) {
    requireApp(app)
    val gone = domains.map(Names.domain)
    val i = info(app)
    save(i.copy(domains = i.domains.filterNot(gone.contains)))
    event(app, "domain", None, s"Removed ${gone.mkString(", ")}")
    syncEdge()
  }

  def domainList(app: String): Unit = {
    requireApp(app)
    info(app).domains.foreach(println)
  }

  // ---- edge

  private def edgeServiceSpec: Service =
    Service(
      EdgeService,
      "unitship edge FreeUnit",
      Seq(
        binDir.resolve("unitd").toString,
        "--no-daemon",
        "--control",
        s"unix:$controlSocket",
        "--pid",
        edgeDir.resolve("unit.pid").toString,
        "--log",
        // Not /dev/stdout: under systemd it is a socket, which FreeUnit cannot open.
        edgeLog.toString,
        "--statedir",
        edgeDir.resolve("state").toString,
        "--tmpdir",
        edgeDir.resolve("tmp").toString
      ),
      edgeDir.toString,
      None,
      edgeLog.toString
    )

  private def control(method: String, path: String, body: Option[Path] = None): Sys.Result =
    Sys.run(
      Seq("curl", "-sS", "--unix-socket", controlSocket.toString, "-X", method) ++
        body.toSeq.flatMap(f => Seq("--data-binary", s"@$f")) :+ s"http://localhost$path"
    )

  private def ensureEdge(): Unit = {
    if (!Files.exists(binDir.resolve("unitd"))) throw new HostError(s"unitd is missing: run `unitship setup`")
    // FreeUnit stores the certificates uploaded to it in this directory and does not create it.
    Files.createDirectories(edgeDir.resolve("state").resolve("certs"))
    supervisor.install(edgeServiceSpec)
    if (!supervisor.isActive(EdgeService)) supervisor.start(EdgeService)
    var waited = 0
    while (!control("GET", "/config").ok && waited < 100) { Sys.sleep(150); waited += 1 }
    if (waited >= 100) {
      System.err.println("the edge did not start, last logs:")
      edgeLogs(20, follow = false)
      throw new HostError("the edge FreeUnit does not answer on its control socket")
    }
  }

  private def certificateDomains: Set[String] =
    Files2.list(certsDir).map(_.getFileName.toString).filter(_.endsWith(".pem")).map(_.stripSuffix(".pem")).toSet

  /** Points the edge to the active release of every app and loads the certificates. */
  def syncEdge(): Unit = {
    ensureEdge()
    val c = config
    val apps = appNames.map { a =>
      val i = info(a)
      EdgeApp(a, i.domains, i.active.flatMap(releasePort(a, _)))
    }
    val used = apps.flatMap(_.domains).toSet
    val certs = certificateDomains.intersect(used)
    val rejected = certs.toSeq.sorted.filterNot { d =>
      val r = control("PUT", s"/certificates/$d", Some(certsDir.resolve(s"$d.pem")))
      r.ok && r.output.contains("success")
    }
    if (rejected.nonEmpty) {
      // FreeUnit on macOS cannot store a certificate uploaded to it. It reads them from its state directory when it
      // starts, so put them there and restart it (a short interruption, only where uploading doesn't work).
      rejected.foreach { d =>
        val target = edgeDir.resolve("state").resolve("certs").resolve(d)
        Files.copy(certsDir.resolve(s"$d.pem"), target, StandardCopyOption.REPLACE_EXISTING)
        Files.setPosixFilePermissions(target, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"))
      }
      say(s"FreeUnit did not accept the certificate of ${rejected.mkString(", ")} through its API: restarting the edge")
      supervisor.stop(EdgeService)
      ensureEdge()
    }
    val json = EdgeConfig.render(apps, certs, c.httpPort, c.httpsPort, webroot.toString, Some(accessLog.toString))
    val file = edgeDir.resolve("conf.json")
    Files2.write(file, json)
    val r = control("PUT", "/config", Some(file))
    if (!r.ok || !r.output.contains("success")) throw new HostError(s"the edge refused its configuration: ${r.output}")
  }

  // ---- TLS

  private lazy val legoMajor: Int =
    Lego.majorVersion(Sys.run(Seq("lego", "--version")).output).getOrElse(4)

  /** Runs lego for `domain`: gets its certificate, or renews it if it expires in less than `renewDays` days. */
  private def lego(email: String, domain: String, renewDays: Option[Int], force: Boolean = false): Int =
    Sys.inherit(Lego.command(legoMajor, email, config.acmeServer, webroot.toString, acmeDir.toString, domain, renewDays, force))

  private def legoEmail(email: Option[String]): String = {
    if (!Sys.exists("lego")) throw new HostError("lego is not installed: https://go-acme.github.io/lego/ (or use `tls import`)")
    email
      .orElse(config.acmeEmail)
      .getOrElse(throw new HostError("an email is needed for Let's Encrypt: pass --email or run `unitship setup --email <email>`"))
  }

  private def installCertificate(domain: String): Boolean = {
    val crt = acmeDir.resolve("certificates").resolve(s"$domain.crt")
    val key = acmeDir.resolve("certificates").resolve(s"$domain.key")
    if (!Files.exists(crt) || !Files.exists(key)) throw new HostError(s"lego did not produce a certificate for $domain")
    val pem = Files2.read(crt).stripSuffix("\n") + "\n" + Files2.read(key)
    val target = certsDir.resolve(s"$domain.pem")
    val changed = !Files.exists(target) || Files2.read(target) != pem
    if (changed) {
      Files2.write(target, pem)
      Files.setPosixFilePermissions(target, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"))
    }
    changed
  }

  /** Gets certificates from Let's Encrypt for the domains of `app` (all of its domains if none is given). */
  def tlsIssue(app: String, domains: Seq[String], email: Option[String]): Unit = {
    requireApp(app)
    val mine = info(app).domains
    val chosen = if (domains.isEmpty) mine else domains.map(Names.domain)
    chosen.filterNot(mine.contains).foreach(d => throw new HostError(s"$d is not a domain of $app: add it with `domains $app add $d`"))
    if (chosen.isEmpty) throw new HostError(s"$app has no domain")
    val e = legoEmail(email)
    chosen.foreach { d =>
      say(s"Getting a certificate for $d")
      if (lego(e, d, None) != 0) throw new HostError(s"could not get a certificate for $d (is $d pointing to this server on port 80?)")
      installCertificate(d)
      event(app, "tls", None, s"Certificate issued for $d")
    }
    syncEdge()
    say(s"HTTPS is on for ${chosen.mkString(", ")}")
  }

  /** Renews the certificates that expire in less than 30 days. Run every day by a timer. */
  def tlsRenew(force: Boolean = false): Unit = {
    requireSetup()
    val domains = certificateDomains.toSeq.sorted.filter(d => Files.exists(acmeDir.resolve("certificates").resolve(s"$d.crt")))
    if (domains.isEmpty) return
    val e = legoEmail(None)
    var changed = false
    domains.foreach { d =>
      if (lego(e, d, Some(30), force) != 0) System.err.println(s"warning: could not renew $d")
      else if (installCertificate(d)) { changed = true; ownerOf(d).foreach(event(_, "tls", None, s"Certificate of $d renewed")) }
    }
    if (changed) syncEdge()
  }

  /** Uses a certificate chain and key obtained elsewhere. */
  def tlsImport(domain: String, chain: Path, key: Path): Unit = {
    requireSetup()
    val d = Names.domain(domain)
    val pem = Files2.read(chain).stripSuffix("\n") + "\n" + Files2.read(key)
    val target = certsDir.resolve(s"$d.pem")
    Files2.write(target, pem)
    Files.setPosixFilePermissions(target, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"))
    ownerOf(d).foreach(event(_, "tls", None, s"Certificate of $d imported"))
    syncEdge()
    say(s"Certificate of $d loaded (it is not renewed by unitship)")
  }

  // ---- events, maintenance and the machine readable state the panel reads

  private def ownerOf(domain: String): Option[String] = appNames.find(a => info(a).domains.contains(domain))

  /** Records what happened to an app. It never fails the operation that is being recorded. */
  private def event(app: String, kind: String, release: Option[Int], message: String): Unit =
    try {
      val file = appDir(app).resolve("events.jsonl")
      Files.write(
        file,
        (Events.line(System.currentTimeMillis(), kind, release, message) + "\n").getBytes("UTF-8"),
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND
      )
      if (Files.size(file) > 256 * 1024)
        Files2.write(file, Files2.read(file).linesIterator.toSeq.takeRight(500).mkString("", "\n", "\n"))
    } catch { case _: java.io.IOException => () }

  private def eventLines(app: String, n: Int): Seq[String] = {
    val file = appDir(app).resolve("events.jsonl")
    if (Files.exists(file)) Files2.read(file).linesIterator.toSeq.takeRight(n) else Nil
  }

  /** The events of an app as a JSON array, the newest last. */
  def apiEvents(app: String, n: Int): String = {
    requireApp(app)
    Events.array(eventLines(app, n))
  }

  private def certificateExpiry(domain: String): Option[Long] =
    if (!Sys.exists("openssl")) None
    else Cert.parseNotAfter(Sys.run(Seq("openssl", "x509", "-noout", "-enddate", "-in", certsDir.resolve(s"$domain.pem").toString)).output)

  private def sizeOf(file: Path): Option[Long] = if (Files.isRegularFile(file)) Some(Files.size(file)) else None

  /** Everything about an app, as JSON: releases with their state and memory, domains with their certificates, the names (never the
    * values) of the environment variables and the latest events. */
  def apiApp(app: String): String = {
    requireApp(app)
    import Json.*
    val i = info(app)
    val certs = certificateDomains
    val domains = i.domains.map { d =>
      obj(
        "name" -> Str(d),
        "https" -> Bool(certs(d)),
        "certificateExpires" -> Json.option(if (certs(d)) certificateExpiry(d).map(ms => Str(Iso.format(ms))) else None)
      )
    }
    val releaseJson = releases(app).reverse.map { r =>
      val dir = releaseDir(app, r)
      val service = serviceName(app, r)
      val running = supervisor.isActive(service)
      val stats = if (running) supervisor.stats(service) else Stats(None, None)
      obj(
        "id" -> Json.num(r),
        "port" -> Json.option(releasePort(app, r).map(Json.num)),
        "active" -> Bool(i.active.contains(r)),
        "previous" -> Bool(i.previous.contains(r)),
        "state" -> Str(if (running) "running" else "stopped"),
        "createdAt" -> Str(Iso.format(Files.getLastModifiedTime(dir).toMillis)),
        "sizeBytes" -> Json.option(sizeOf(dir.resolve("app")).map(Num(_))),
        "memoryBytes" -> Json.option(stats.memoryBytes.map(Num(_))),
        "cpuNanos" -> Json.option(stats.cpuNanos.map(Num(_)))
      )
    }
    obj(
      "name" -> Str(app),
      "domains" -> Arr(domains),
      "active" -> Json.option(i.active.map(Json.num)),
      "previous" -> Json.option(i.previous.map(Json.num)),
      "releases" -> Arr(releaseJson),
      "env" -> Arr(env(app).keys.toSeq.sorted.map(Str(_))),
      "events" -> Raw(Events.array(eventLines(app, 30)))
    ).render
  }

  private def readProc(file: String): Option[String] =
    Option(Paths.get(file)).filter(Files.exists(_)).map(p => Files2.read(p))

  private def sysctl(name: String): Option[String] =
    Some(Sys.run(Seq("sysctl", "-n", name))).filter(_.ok).map(_.output)

  /** Load, memory and uptime of the machine: from /proc on Linux, from sysctl and vm_stat on macOS. */
  private final case class Machine(load: Option[Seq[Double]], memoryTotal: Option[Long], memoryAvailable: Option[Long], uptimeSeconds: Option[Long])

  private def machine(): Machine =
    if (Sys.isLinux) {
      val meminfo = readProc("/proc/meminfo").map(
        _.linesIterator.flatMap(_.split("\\s+").toList match {
          case key :: value :: _ => value.toLongOption.map(key.stripSuffix(":") -> _ * 1024)
          case _                 => None
        }).toMap
      )
      Machine(
        readProc("/proc/loadavg").map(_.trim.split("\\s+").take(3).flatMap(_.toDoubleOption).toSeq).filter(_.length == 3),
        meminfo.flatMap(_.get("MemTotal")),
        meminfo.flatMap(_.get("MemAvailable")),
        readProc("/proc/uptime").flatMap(_.trim.split("\\s+").headOption).flatMap(_.toDoubleOption).map(_.toLong)
      )
    } else {
      val load = sysctl("vm.loadavg").map(_.replaceAll("[{}]", "").trim.split("\\s+").flatMap(_.toDoubleOption).toSeq).filter(_.length == 3)
      val boot = sysctl("kern.boottime").flatMap("sec = (\\d+)".r.findFirstMatchIn(_)).flatMap(_.group(1).toLongOption)
      // Free, inactive and speculative pages are available to a program.
      val vm = Sys.run(Seq("vm_stat")).output
      val pageSize = "page size of (\\d+) bytes".r.findFirstMatchIn(vm).flatMap(_.group(1).toLongOption)
      def pages(label: String) = s"$label:\\s+(\\d+)".r.findFirstMatchIn(vm).flatMap(_.group(1).toLongOption)
      val available = for {
        size <- pageSize
        free <- pages("Pages free")
        inactive <- pages("Pages inactive")
      } yield (free + inactive + pages("Pages speculative").getOrElse(0L)) * size
      Machine(load, sysctl("hw.memsize").flatMap(_.toLongOption), available, boot.map(b => System.currentTimeMillis() / 1000 - b))
    }

  /** Load, memory, uptime and disk of the machine. */
  private def hostJson: Json = {
    import Json.*
    val m = machine()
    val disk = Sys.run(Seq("df", "-Pk", home.toString)).output.linesIterator.toSeq.lastOption.map(_.trim.split("\\s+")).collect {
      case f if f.length >= 4 => (f(1).toLongOption, f(3).toLongOption)
    }
    obj(
      "load" -> Json.option(m.load.map(l => Arr(l.map(Json.decimal)))),
      "memoryTotalBytes" -> Json.option(m.memoryTotal.map(Num(_))),
      "memoryAvailableBytes" -> Json.option(m.memoryAvailable.map(Num(_))),
      "uptimeSeconds" -> Json.option(m.uptimeSeconds.map(Num(_))),
      "diskTotalBytes" -> Json.option(disk.flatMap(_._1).map(k => Num(k * 1024))),
      "diskAvailableBytes" -> Json.option(disk.flatMap(_._2).map(k => Num(k * 1024)))
    )
  }

  /** The state of the host as JSON: the machine, the edge (with FreeUnit's own status) and a summary of every app. */
  def apiStatus(): String = {
    requireSetup()
    import Json.*
    val c = config
    val edgeRunning = supervisor.isActive(EdgeService)
    val edgeStatus = Some(edgeRunning).filter(identity).map(_ => control("GET", "/status")).filter(_.ok).map(_.output)
    val certs = certificateDomains
    val apps = appNames.map { a =>
      val i = info(a)
      val service = i.active.map(serviceName(a, _))
      obj(
        "name" -> Str(a),
        "domains" -> Arr(i.domains.map(Str(_))),
        "release" -> Json.option(i.active.map(Json.num)),
        "port" -> Json.option(i.active.flatMap(releasePort(a, _)).map(Json.num)),
        "state" -> Str(service.map(s => if (supervisor.isActive(s)) "running" else "stopped").getOrElse("none")),
        "https" -> Bool(i.domains.exists(certs)),
        "httpsDomains" -> Arr(i.domains.filter(certs).map(Str(_))),
        "memoryBytes" -> Json.option(service.filter(supervisor.isActive).flatMap(supervisor.stats(_).memoryBytes).map(Num(_)))
      )
    }
    obj(
      "host" -> hostJson,
      "edge" -> obj(
        "state" -> Str(if (edgeRunning) "running" else "stopped"),
        "httpPort" -> Json.num(c.httpPort),
        "httpsPort" -> Json.num(c.httpsPort),
        "accessLog" -> Str(accessLog.toString),
        "status" -> Json.option(edgeStatus.map(Raw(_)))
      ),
      "apps" -> Arr(apps)
    ).render
  }

  // ---- metrics

  /** The lines the edge added to its access log since the last time, whole lines only. */
  private def newAccessLines(): Seq[String] =
    if (!Files.exists(accessLog)) Nil
    else {
      val stateFile = metricsDir.resolve("access.state")
      val saved = Files2.readProps(stateFile).get("offset").flatMap(_.toLongOption).getOrElse(0L)
      val file = new java.io.RandomAccessFile(accessLog.toFile, "r")
      try {
        val length = file.length()
        // A shorter file is a rotated one: start over.
        val start = if (length < saved) 0L else saved
        val bytes = new Array[Byte](Math.min(length - start, 64L * 1024 * 1024).toInt)
        file.seek(start)
        file.readFully(bytes)
        val consumed = bytes.lastIndexOf('\n'.toByte) + 1
        Files2.writeProps(stateFile, Map("offset" -> (start + consumed).toString))
        new String(bytes, 0, consumed, "UTF-8").linesIterator.toSeq
      } finally file.close()
    }

  private def appendPoint(name: String, line: String): Unit = {
    val file = metricsDir.resolve(s"$name.jsonl")
    Files.write(file, (line + "\n").getBytes("UTF-8"), StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    // A week of one point a minute.
    if (Files.size(file) > 4 * 1024 * 1024)
      Files2.write(file, Files2.read(file).linesIterator.toSeq.takeRight(10080).mkString("", "\n", "\n"))
  }

  /**
   * Adds a point to the metrics of every app and of the host: what the edge served since the last point (from its access
   * log), and the memory and CPU of the active release. A timer runs it every minute, and `api metrics` runs it when the
   * last point is old.
   */
  def collect(): Unit = {
    requireSetup()
    Files.createDirectories(metricsDir)
    val lock = metricsDir.resolve(".lock")
    // Another collection in progress: skip. A lock older than two minutes is a crashed one.
    if (Files.exists(lock) && System.currentTimeMillis() - Files.getLastModifiedTime(lock).toMillis > 120000) Files.delete(lock)
    try Files.createFile(lock)
    catch { case _: FileAlreadyExistsException => return }
    try {
      val now = System.currentTimeMillis()
      val lastFile = metricsDir.resolve("last.state")
      val seconds = Files2
        .readProps(lastFile)
        .get("ms")
        .flatMap(_.toLongOption)
        .map(ms => Math.max(1.0, Math.min(3600.0, (now - ms) / 1000.0)))
        .getOrElse(60.0)
      val appOf = appNames.flatMap(a => info(a).domains.map(_ -> a)).toMap
      val byApp = newAccessLines().flatMap(AccessLog.parse).groupBy(e => appOf.get(e.host))
      appNames.foreach { a =>
        val i = info(a)
        val service = i.active.map(serviceName(a, _)).filter(supervisor.isActive)
        val stats = service.map(supervisor.stats)
        val cpuFile = metricsDir.resolve(s"$a.cpu")
        val before = Files2.readProps(cpuFile)
        val cpu = for {
          nanos <- stats.flatMap(_.cpuNanos)
          prevNanos <- before.get("nanos").flatMap(_.toLongOption)
          prevMs <- before.get("ms").flatMap(_.toLongOption)
          if before.get("service").contains(service.getOrElse(""))
          percent <- Metrics.cpuPercent(prevNanos, prevMs, nanos, now)
        } yield percent
        stats.flatMap(_.cpuNanos) match {
          case Some(n) => Files2.writeProps(cpuFile, Map("nanos" -> n.toString, "ms" -> now.toString, "service" -> service.getOrElse("")))
          case None    => Files.deleteIfExists(cpuFile)
        }
        appendPoint(a, Metrics.point(now, seconds, Metrics.aggregate(byApp.getOrElse(Some(a), Nil)), stats.flatMap(_.memoryBytes), cpu))
      }
      val m = machine()
      import Json.*
      appendPoint(
        "_host",
        obj(
          "ms" -> Num(now),
          "t" -> Str(Iso.format(now)),
          "load1" -> Json.option(m.load.flatMap(_.headOption).map(Json.decimal)),
          "memoryAvailableBytes" -> Json.option(m.memoryAvailable.map(Num(_))),
          "memoryTotalBytes" -> Json.option(m.memoryTotal.map(Num(_)))
        ).render
      )
      Files2.writeProps(lastFile, Map("ms" -> now.toString))
    } finally Files.deleteIfExists(lock)
  }

  /** The points of an app (or of `_host`) of the last `minutes` minutes, as a JSON array, oldest first. */
  def apiMetrics(name: String, minutes: Int): String = {
    requireSetup()
    if (name != "_host") requireApp(name)
    val last = Files2.readProps(metricsDir.resolve("last.state")).get("ms").flatMap(_.toLongOption).getOrElse(0L)
    if (System.currentTimeMillis() - last > 55000) collect()
    val file = metricsDir.resolve(s"$name.jsonl")
    val lines = if (Files.exists(file)) Files2.read(file).linesIterator.toSeq else Nil
    Metrics.since(lines, System.currentTimeMillis() - minutes * 60000L)
  }

  /** Daily: renews the certificates and rotates the logs of the edge. */
  def maintenance(): Unit = {
    requireSetup()
    val limit = sys.env.get("UNITSHIP_LOG_ROTATE_BYTES").flatMap(_.toLongOption).getOrElse(20L * 1024 * 1024)
    val rotated = List(accessLog, edgeLog).filter(f => Files.exists(f) && Files.size(f) > limit).map { f =>
      Files.move(f, f.resolveSibling(f.getFileName.toString + ".1"), StandardCopyOption.REPLACE_EXISTING)
      f
    }
    // FreeUnit opens its log files again when it gets SIGUSR1.
    if (rotated.nonEmpty)
      Option(edgeDir.resolve("unit.pid")).filter(Files.exists(_)).foreach { p =>
        Sys.run(Seq("kill", "-USR1", Files2.read(p).trim))
      }
    rotated.foreach(f => say(s"Rotated ${f.getFileName}"))
    tlsRenew()
  }

  // ---- git

  def gitInit(app: String): Unit = {
    requireApp(app)
    val repo = gitDir.resolve(s"$app.git")
    if (!Files.exists(repo)) {
      val r = Sys.run(Seq("git", "init", "--bare", "-q", repo.toString))
      if (!r.ok) throw new HostError(s"git init failed: ${r.output}")
    }
    val hook = repo.resolve("hooks").resolve("post-receive")
    Files2.write(
      hook,
      s"#!/bin/sh\nUNITSHIP_HOME=${Shell.quote(home.toString)} exec ${Shell.quote(config.bin)} git-receive ${Shell.quote(app)}\n"
    )
    Files2.makeExecutable(hook)
    say(s"Push to deploy $app: git remote add unitship <ssh-login>@<server>:$repo && git push unitship main")
    println(s"The server builds it with `snunit package .` (needs snunit and scalino installed here); set UNITSHIP_BUILD_COMMAND to build another way.")
  }

  /** Called by the `post-receive` hook with the pushed refs on the standard input. */
  def gitReceive(app: String, refs: Seq[String]): Unit = {
    requireApp(app)
    val branch = env(app).getOrElse("UNITSHIP_DEPLOY_BRANCH", "main")
    val pushed = refs.map(_.trim.split("\\s+")).collect { case Array(_, newRev, ref) if ref == s"refs/heads/$branch" => newRev }
    pushed.lastOption match {
      case None => say(s"Nothing to deploy: only pushes to $branch are deployed")
      case Some(rev) =>
        val repo = gitDir.resolve(s"$app.git")
        val work = Files.createTempDirectory(appDir(app), "build")
        try {
          say(s"Building ${rev.take(8)}")
          val unpack = Sys.run(
            Seq("sh", "-c", s"git --git-dir=${Shell.quote(repo.toString)} archive ${Shell.quote(rev)} | tar -x -C ${Shell.quote(work.toString)}")
          )
          if (!unpack.ok) throw new HostError(s"cannot read the pushed commit: ${unpack.output}")
          val userEnv = env(app)
          val command = userEnv.getOrElse(
            "UNITSHIP_BUILD_COMMAND",
            s"snunit package ${userEnv.getOrElse("UNITSHIP_BUILD_ARGS", ".")} -o app"
          )
          // The hook runs with GIT_DIR set, which would confuse a build that uses git.
          val code = Sys.inherit(Seq("sh", "-c", s"unset GIT_DIR GIT_WORK_TREE; $command"), userEnv, Some(work))
          if (code != 0) throw new HostError(s"the build failed (exit code $code)")
          if (!Files.isRegularFile(work.resolve("app"))) throw new HostError("the build did not produce the executable ./app")
          deploy(app, work.resolve("app"))
        } finally Files2.deleteRecursively(work)
    }
  }
}
