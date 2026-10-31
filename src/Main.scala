package unitship

import java.nio.file.*

// `unitship`: a small self-hosted platform for SNUnit applications, in the spirit of Dokku but with no Docker.
//
// Build it with: scalino package vendor src -o unitship

// Keep in sync with the release tag (checked by .github/workflows/release.yml).
private val version = "0.1.0"

private val usage =
  """unitship: deploy SNUnit applications to your own server.
    |
    |On the server (once):
    |  unitship setup [--http-port <n>] [--https-port <n>] [--base-domain <domain>] [--email <email>]
    |                    [--acme-server <url|letsencrypt-staging>] [--supervisor systemd|local]
    |
    |Apps:
    |  unitship create <app>
    |  unitship destroy <app> --yes                     deletes its releases and its data
    |  unitship ps                                      apps, releases, ports and state
    |  unitship deploy <app> <executable>               new release, switched to with no downtime
    |  unitship releases <app>
    |  unitship rollback <app> [<release>]
    |  unitship config <app>                            show the environment
    |  unitship config <app> set KEY=value... [--no-restart]
    |  unitship config <app> unset KEY... [--no-restart]
    |  unitship domains <app> [add|remove <domain>...]
    |  unitship start|stop|restart <app>
    |  unitship exec <app> [--] <args>...                 run the executable of the app with its environment (a task)
    |  unitship logs <app> [-n <lines>] [-f]            `logs --edge` for the proxy
    |
    |HTTPS:
    |  unitship tls <app> [<domain>...] [--email <email>]   Let's Encrypt certificates (needs lego)
    |  unitship tls import <domain> <chain.pem> <key.pem>
    |  unitship tls renew [--force]                     run every day by a systemd timer; --force renews now
    |
    |Maintenance and machine readable state (for the panel):
    |  unitship maintenance                                run every day by a systemd timer: renews certificates,
    |                                                      rotates the logs of the edge
    |  unitship collect                                    adds a point to the metrics (run every minute by a timer)
    |  unitship api status | api app <app> | api events <app> [-n <n>] | api metrics <app>|_host [-n <minutes>]     JSON
    |
    |Deploy with `git push`:
    |  unitship git init <app>                          then `git push` to the remote it prints
    |
    |From your machine, run any command on the server over ssh:
    |  unitship --host user@server deploy my-app ./my-app
    |  UNITSHIP_HOST=user@server unitship ps
    |
    |Your app must listen on 127.0.0.1:$PORT (see README.md).
    |
    |Environment:
    |  UNITSHIP_HOST      ssh destination: run the command on that server
    |  UNITSHIP_BIN       unitship on the server, if it is not in the ssh PATH
    |  UNITSHIP_HOME      state directory on the server (default: ~/.unitship)
    |  SNUNIT_FREEUNIT_DIR   directory with `unitd`, instead of downloading it""".stripMargin

private def die(message: String): Nothing = {
  System.err.println(s"unitship: $message")
  sys.exit(1)
}

/** Takes `<name> <value>` out of `args`. */
private def option(args: List[String], name: String): (List[String], Option[String]) = {
  val i = args.indexOf(name)
  if (i < 0) (args, None)
  else if (i == args.length - 1) die(s"$name needs a value")
  else (args.take(i) ++ args.drop(i + 2), Some(args(i + 1)))
}

private def flag(args: List[String], names: String*): (List[String], Boolean) =
  (args.filterNot(names.contains), args.exists(names.contains))

private def intOption(args: List[String], name: String): (List[String], Option[Int]) = {
  val (rest, value) = option(args, name)
  (rest, value.map(v => v.toIntOption.getOrElse(die(s"$name expects a number, not '$v'"))))
}

private def defaultHome: Path =
  sys.env
    .get("UNITSHIP_HOME")
    .map(Paths.get(_))
    .getOrElse(Paths.get(System.getProperty("user.home"), ".unitship"))
    .toAbsolutePath

/** Runs the command on the server with ssh. `deploy` sends the executable through the standard input. */
private def remote(destination: String, args: List[String]): Int = {
  val bin = sys.env.getOrElse("UNITSHIP_BIN", "unitship")
  args match {
    case "deploy" :: app :: file :: Nil =>
      val path = Paths.get(file)
      if (!Files.isRegularFile(path)) die(s"$file is not a file")
      Sys.inherit(Seq("ssh", destination, Seq(bin, "deploy", app, "--stdin").map(Shell.quote).mkString(" ")), stdin = Some(path))
    case _ =>
      // exec may ask for a password: it needs a terminal.
      val tty = if (args.headOption.contains("exec")) Seq("-t") else Nil
      Sys.inherit(Seq("ssh") ++ tty ++ Seq(destination, (bin +: args).map(Shell.quote).mkString(" ")))
  }
}

private def appDirectory(host: Host, app: String): Path = {
  val dir = host.home.resolve("apps").resolve(Names.app(app))
  if (!Files.isDirectory(dir)) throw new HostError(s"unknown app '$app': create it with `unitship create $app`")
  dir
}

/** The executable of a deploy that arrives through the standard input. */
private def receiveStdin(host: Host, app: String): Path = {
  val file = Files.createTempFile(appDirectory(host, app), "upload", ".tmp")
  val out = Files.newOutputStream(file)
  try System.in.transferTo(out)
  finally out.close()
  file
}

@main def unitship(allArgs: String*): Unit = {
  var args = allArgs.toList
  val (afterHome, homeArg) = option(args, "--home")
  val (afterHost, hostArg) = option(afterHome, "--host")
  args = afterHost
  val destination = hostArg.orElse(sys.env.get("UNITSHIP_HOST").filter(_.nonEmpty))

  args match {
    case Nil | ("-h" | "--help" | "help") :: _ => println(usage)
    case ("-V" | "--version" | "version") :: _ => println(s"unitship $version")
    case _ if destination.nonEmpty && !args.headOption.contains("git-receive") =>
      try sys.exit(remote(destination.get, args))
      catch {
        case e: HostError => die(e.getMessage)
      }
    case _ =>
      val host = new Host(homeArg.map(Paths.get(_).toAbsolutePath).getOrElse(defaultHome))
      try sys.exit(run(host, args))
      catch {
        case e: HostError => die(e.getMessage)
      }
  }
}

private def run(host: Host, args: List[String]): Int = {
  args match {
    case "setup" :: rest0 =>
      val (rest1, http) = intOption(rest0, "--http-port")
      val (rest2, https) = intOption(rest1, "--https-port")
      val (rest3, base) = option(rest2, "--base-domain")
      val (rest4, email) = option(rest3, "--email")
      val (rest5, acmeServer) = option(rest4, "--acme-server")
      val (rest6, kind) = option(rest5, "--supervisor")
      val (rest7, bin) = option(rest6, "--bin")
      if (rest7.nonEmpty) die(s"unexpected arguments: ${rest7.mkString(" ")}")
      kind.foreach(k => if (k != "systemd" && k != "local") die("--supervisor is systemd or local"))
      host.setup(http, https, base, email, acmeServer, kind, bin)
    case "create" :: app :: Nil => host.create(app)
    case "destroy" :: app :: rest =>
      val (_, yes) = flag(rest, "--yes", "-y")
      if (!yes) die(s"this deletes '$app', its releases and its data: pass --yes to confirm")
      host.destroy(app)
    case "ps" :: Nil => host.ps()
    case "deploy" :: app :: "--stdin" :: Nil =>
      val file = receiveStdin(host, app)
      try host.deploy(app, file)
      finally Files.deleteIfExists(file)
    case "deploy" :: app :: file :: Nil =>
      // The executable is moved into the release: keep the user's file.
      val copy = Files.createTempFile(appDirectory(host, app), "upload", ".tmp")
      try {
        Files.copy(Paths.get(file), copy, StandardCopyOption.REPLACE_EXISTING)
        host.deploy(app, copy)
      } finally Files.deleteIfExists(copy)
    case "releases" :: app :: Nil => host.releaseList(app)
    case "rollback" :: app :: Nil => host.rollback(app, None)
    case "rollback" :: app :: r :: Nil =>
      host.rollback(app, Some(r.toIntOption.getOrElse(die(s"'$r' is not a release number"))))
    case "config" :: app :: Nil =>
      host.env(app).toSeq.sortBy(_._1).foreach((k, v) => println(s"$k=$v"))
    case "config" :: app :: "set" :: rest =>
      val (pairs, noRestart) = flag(rest, "--no-restart")
      if (pairs.isEmpty) die("usage: config <app> set KEY=value...")
      val set = pairs.map { p =>
        val i = p.indexOf('=')
        if (i <= 0) die(s"'$p' is not KEY=value")
        p.substring(0, i) -> p.substring(i + 1)
      }.toMap
      host.changeEnv(app, set, Nil, restart = !noRestart)
    case "config" :: app :: "unset" :: rest =>
      val (keys, noRestart) = flag(rest, "--no-restart")
      if (keys.isEmpty) die("usage: config <app> unset KEY...")
      host.changeEnv(app, Map.empty, keys, restart = !noRestart)
    case "domains" :: app :: Nil => host.domainList(app)
    case "domains" :: app :: "add" :: ds if ds.nonEmpty => host.addDomains(app, ds)
    case "domains" :: app :: "remove" :: ds if ds.nonEmpty => host.removeDomains(app, ds)
    case "start" :: app :: Nil => host.start(app)
    case "stop" :: app :: Nil => host.stop(app)
    case "restart" :: app :: Nil => host.restart(app)
    case "exec" :: app :: rest   => return host.exec(app, rest.dropWhile(_ == "--"))
    case "logs" :: rest0 =>
      val (rest1, lines) = intOption(rest0, "-n")
      val (rest2, follow) = flag(rest1, "-f", "--follow")
      val (rest3, edge) = flag(rest2, "--edge")
      (rest3, edge) match {
        case (Nil, true)         => return host.logs(None, lines.getOrElse(100), follow)
        case (app :: Nil, false) => return host.logs(Some(app), lines.getOrElse(100), follow)
        case _                   => die("usage: logs <app> [-n <lines>] [-f] | logs --edge")
      }
    case "tls" :: "renew" :: Nil => host.tlsRenew()
    case "tls" :: "renew" :: "--force" :: Nil => host.tlsRenew(force = true)
    case "maintenance" :: Nil     => host.maintenance()
    case "collect" :: Nil         => host.collect()
    case "api" :: "metrics" :: name :: rest =>
      val (_, minutes) = intOption(rest, "-n")
      println(host.apiMetrics(name, minutes.getOrElse(60)))
    case "api" :: "status" :: Nil => println(host.apiStatus())
    case "api" :: "app" :: app :: Nil => println(host.apiApp(app))
    case "api" :: "events" :: app :: rest =>
      val (_, n) = intOption(rest, "-n")
      println(host.apiEvents(app, n.getOrElse(100)))
    case "tls" :: "import" :: domain :: chain :: key :: Nil =>
      host.tlsImport(domain, Paths.get(chain), Paths.get(key))
    case "tls" :: app :: rest0 =>
      val (domains, email) = option(rest0, "--email")
      host.tlsIssue(app, domains, email)
    case "git" :: "init" :: app :: Nil => host.gitInit(app)
    case "git-receive" :: app :: Nil =>
      host.gitReceive(app, scala.io.Source.stdin.getLines().toSeq)
    case other => die(s"unknown command: ${other.mkString(" ")}\n\n$usage")
  }
  0
}
