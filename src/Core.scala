package unitship

// Pure logic of unitship: names, environment files, the configuration of the edge FreeUnit, systemd units, ports
// and releases. Nothing here touches the file system or runs a process, so it is tested on the JVM
// (see test/) and compiled to native with the rest.

/** A failure with a message meant to be shown to the user as is. */
final class HostError(message: String) extends RuntimeException(message)

/** The little JSON needed to configure FreeUnit and to answer the panel. */
enum Json {
  case Obj(fields: Seq[(String, Json)])
  case Arr(items: Seq[Json])
  case Str(value: String)
  case Num(value: Long)
  case Bool(value: Boolean)
  case Null

  /** JSON that is already rendered, like the status FreeUnit answers. */
  case Raw(json: String)

  def render: String = this match {
    case Obj(fields) => fields.map((k, v) => s"${Json.quote(k)}:${v.render}").mkString("{", ",", "}")
    case Arr(items)  => items.map(_.render).mkString("[", ",", "]")
    case Str(value)  => Json.quote(value)
    case Num(value)  => value.toString
    case Bool(value) => value.toString
    case Null        => "null"
    case Raw(json)   => json
  }
}

object Json {
  def obj(fields: (String, Json)*): Json = Obj(fields)
  def num(value: Int): Json = Num(value.toLong)
  def option(value: Option[Json]): Json = value.getOrElse(Null)

  /** A number with two decimals. Always a point, whatever the locale of the machine is. */
  def decimal(value: Double): Json =
    if (value.isNaN || value.isInfinite) Null
    else {
      val hundredths = Math.round(value * 100)
      val digits = Math.abs(hundredths)
      Raw(s"${if (hundredths < 0) "-" else ""}${digits / 100}.${(digits % 100).toString.reverse.padTo(2, '0').reverse}")
    }

  def quote(s: String): String = {
    val out = new StringBuilder("\"")
    s.foreach {
      case '"'          => out ++= "\\\""
      case '\\'         => out ++= "\\\\"
      case '\n'         => out ++= "\\n"
      case '\r'         => out ++= "\\r"
      case '\t'         => out ++= "\\t"
      case c if c < ' ' => out ++= f"\\u${c.toInt}%04x"
      case c            => out += c
    }
    out += '"'
    out.toString
  }
}

/** `key=value` files, one entry per line. Used for the state of the host, the apps and the releases. */
object Props {
  def parse(text: String): Map[String, String] =
    text.linesIterator
      .map(_.trim)
      .filter(l => l.nonEmpty && !l.startsWith("#") && l.contains('='))
      .map { l =>
        val i = l.indexOf('=')
        l.substring(0, i).trim -> l.substring(i + 1).trim
      }
      .toMap

  def render(props: Map[String, String]): String =
    props.toSeq.sortBy(_._1).map((k, v) => s"$k=$v\n").mkString
}

/** The environment of an app. The same file is read by systemd (`EnvironmentFile`) and by this tool. */
object EnvFile {
  private val Key = "[A-Za-z_][A-Za-z0-9_]*".r

  def validKey(key: String): Boolean = Key.matches(key)

  private def quote(value: String): String = {
    val out = new StringBuilder("\"")
    value.foreach {
      case '\\' => out ++= "\\\\"
      case '"'  => out ++= "\\\""
      case '\n' => out ++= "\\n"
      case c    => out += c
    }
    out += '"'
    out.toString
  }

  private def unquote(raw: String): String =
    if (raw.length >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) {
      val inner = raw.substring(1, raw.length - 1)
      val out = new StringBuilder
      var i = 0
      while (i < inner.length) {
        val c = inner.charAt(i)
        if (c == '\\' && i + 1 < inner.length) {
          inner.charAt(i + 1) match {
            case 'n' => out += '\n'
            case n   => out += n
          }
          i += 2
        } else {
          out += c
          i += 1
        }
      }
      out.toString
    } else raw

  def render(env: Map[String, String]): String =
    env.toSeq.sortBy(_._1).map((k, v) => s"$k=${quote(v)}\n").mkString

  def parse(text: String): Map[String, String] =
    text.linesIterator
      .map(_.trim)
      .filter(l => l.nonEmpty && !l.startsWith("#") && l.contains('='))
      .map { l =>
        val i = l.indexOf('=')
        l.substring(0, i).trim -> unquote(l.substring(i + 1).trim)
      }
      .toMap
}

object Names {
  private val App = "[a-z]([a-z0-9-]{0,30}[a-z0-9])?".r
  private val Label = "[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?".r

  def app(name: String): String =
    if (App.matches(name)) name
    else throw new HostError(s"invalid app name '$name': use lowercase letters, digits and '-', starting with a letter")

  def domain(raw: String): String = {
    val name = raw.toLowerCase
    val labels = name.split('.')
    if (name.length <= 253 && labels.length >= 2 && labels.forall(Label.matches(_))) name
    else throw new HostError(s"invalid domain '$raw'")
  }

  /** Variables the host sets for the app: they can't be changed with `config set`. */
  val Reserved: Set[String] = Set("PORT", "BIND_ADDRESS", "DATA_DIR", "UNITSHIP_APP", "UNITSHIP_RELEASE", "UNITSHIP_HOME", "UNITSHIP_BIN")
}

/** An app with its domains and the port of its active release, if it has one. */
final case class EdgeApp(name: String, domains: Seq[String], port: Option[Int])

/** The configuration of the edge FreeUnit: it listens on the HTTP and HTTPS ports and proxies by `Host`. */
object EdgeConfig {

  /**
   * @param certificates
   *   domains that have a certificate: they are served over HTTPS and HTTP redirects to it
   * @param webroot
   *   directory where the ACME client puts the HTTP-01 challenges
   */
  def render(
      apps: Seq[EdgeApp],
      certificates: Set[String],
      httpPort: Int,
      httpsPort: Int,
      webroot: String,
      accessLog: Option[String] = None
  ): String = {
    import Json.*
    val sites = apps.flatMap(a => a.domains.map(d => (d, a.port))).sortBy(_._1)

    def hostMatch(domain: String) = obj("host" -> Str(domain))
    def proxy(port: Int) = obj("proxy" -> Str(s"http://127.0.0.1:$port"))
    def unavailable = obj("return" -> Num(503L))
    val httpsLocation = if (httpsPort == 443) "https://$host$request_uri" else s"https://$$host:$httpsPort$$request_uri"

    val acme = obj(
      "match" -> obj("uri" -> Str("/.well-known/acme-challenge/*")),
      "action" -> obj("share" -> Str(webroot + "$uri"))
    )
    val http = sites.map { (domain, port) =>
      val action =
        if (certificates(domain)) obj("return" -> Num(301L), "location" -> Str(httpsLocation))
        else port.map(proxy).getOrElse(unavailable)
      obj("match" -> hostMatch(domain), "action" -> action)
    }
    val https = sites.filter((d, _) => certificates(d)).map { (domain, port) =>
      obj("match" -> hostMatch(domain), "action" -> port.map(proxy).getOrElse(unavailable))
    }
    val notFound = obj("action" -> obj("return" -> Num(404L)))

    val tls = certificates.intersect(sites.map(_._1).toSet).toSeq.sorted
    val listeners = Seq(s"*:$httpPort" -> obj("pass" -> Str("routes/http"))) ++
      (if (tls.isEmpty) Nil
       else
         Seq(
           s"*:$httpsPort" -> obj(
             "pass" -> Str("routes/https"),
             "tls" -> obj("certificate" -> Arr(tls.map(Str(_))))
           )
         ))
    val routes = Seq("http" -> Arr(acme +: http :+ notFound)) ++
      (if (tls.isEmpty) Nil else Seq("https" -> Arr(https :+ notFound)))

    obj(
      Seq("listeners" -> Obj(listeners), "routes" -> Obj(routes)) ++
        accessLog.map(path => "access_log" -> obj("path" -> Str(path), "format" -> Str(AccessLog.Format)))*
    ).render
  }
}

/** A process to keep running, with systemd or on its own. */
final case class Service(
    name: String,
    description: String,
    command: Seq[String],
    workDir: String,
    envFile: Option[String],
    logFile: String
)

object SystemdUnit {
  private def quoteArg(arg: String): String =
    if (arg.nonEmpty && !arg.exists(c => c.isWhitespace || "\"'\\$%".contains(c))) arg
    else
      arg.flatMap {
        case '"'  => "\\\""
        case '\\' => "\\\\"
        case '$'  => "$$"
        case '%'  => "%%"
        case c    => c.toString
      }.mkString("\"", "", "\"")

  /** A unit for `systemctl --user`. */
  def render(s: Service): String = {
    val lines = Seq(
      "[Unit]",
      s"Description=${s.description}",
      "After=network.target",
      "",
      "[Service]",
      s"ExecStart=${s.command.map(quoteArg).mkString(" ")}",
      s"WorkingDirectory=${quoteArg(s.workDir)}"
    ) ++ s.envFile.map(f => s"EnvironmentFile=$f") ++ Seq(
      "MemoryAccounting=yes",
      "CPUAccounting=yes",
      "Restart=always",
      "RestartSec=2",
      "KillMode=mixed",
      "TimeoutStopSec=40",
      "LimitNOFILE=65536",
      "NoNewPrivileges=yes",
      "",
      "[Install]",
      "WantedBy=default.target",
      ""
    )
    lines.mkString("\n")
  }

  val RenewService: String =
    """[Unit]
      |Description=Daily maintenance of unitship: renew the TLS certificates and rotate the logs
      |
      |[Service]
      |Type=oneshot
      |ExecStart=%s maintenance
      |""".stripMargin

  val CollectService: String =
    """[Unit]
      |Description=Collect the metrics of unitship
      |
      |[Service]
      |Type=oneshot
      |ExecStart=%s collect
      |""".stripMargin

  val CollectTimer: String =
    """[Unit]
      |Description=Collect the metrics of unitship every minute
      |
      |[Timer]
      |OnCalendar=minutely
      |AccuracySec=5s
      |
      |[Install]
      |WantedBy=timers.target
      |""".stripMargin

  val RenewTimer: String =
    """[Unit]
      |Description=Daily maintenance of unitship
      |
      |[Timer]
      |OnCalendar=daily
      |RandomizedDelaySec=1h
      |Persistent=true
      |
      |[Install]
      |WantedBy=timers.target
      |""".stripMargin
}

object Ports {

  /** A port of `from..to` that is not in `used` and passes `isFree`, searching from `start` and wrapping around. */
  def allocate(used: Set[Int], isFree: Int => Boolean, start: Int, from: Int = 20000, to: Int = 29999): Option[Int] = {
    val size = to - from + 1
    val first = if (start >= from && start <= to) start - from else 0
    (0 until size).iterator.map(i => from + (first + i) % size).find(p => !used(p) && isFree(p))
  }
}

object Releases {

  /** Releases to delete: all but the newest `keep`, the active one and the previous one (the target of `rollback`). */
  def toPrune(all: Seq[Int], active: Option[Int], previous: Option[Int], keep: Int): Seq[Int] = {
    val kept = all.sorted.takeRight(keep).toSet ++ active ++ previous
    all.filterNot(kept)
  }

  /** The release `rollback` activates: the one asked for, or the newest older than the active one. */
  def rollbackTarget(all: Seq[Int], active: Option[Int], requested: Option[Int]): Either[String, Int] =
    requested match {
      case Some(r) if !all.contains(r)  => Left(s"release $r does not exist (available: ${all.sorted.mkString(", ")})")
      case Some(r) if active.contains(r) => Left(s"release $r is already active")
      case Some(r)                       => Right(r)
      case None =>
        all.filter(r => active.forall(r < _)).maxOption.toRight("there is no older release to roll back to")
    }
}

object Shell {

  /** Quotes `s` for a POSIX shell. */
  def quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}

/** The command line of the ACME client, lego. Its version 5 changed the commands and the options of version 4. */
object Lego {
  def majorVersion(output: String): Option[Int] =
    "(\\d+)\\.\\d+(\\.\\d+)?".r.findFirstMatchIn(output).map(_.group(1).toInt)

  /**
   * Gets a certificate for `domain` with the HTTP-01 challenge written into `webroot`, or renews it if it expires in
   * less than `renewDays` days. Both versions store `certificates/<domain>.crt` and `.key` in `path`.
   */
  def command(
      major: Int,
      email: String,
      server: Option[String],
      webroot: String,
      path: String,
      domain: String,
      renewDays: Option[Int],
      force: Boolean = false
  ): Seq[String] = {
    val options = Seq("--accept-tos", "--email", email) ++ server.toSeq.flatMap(Seq("--server", _)) ++
      Seq("--http", "--http.webroot", webroot, "--path", path, "--domains", domain)
    // No random sleep: the daily timer already spreads the renewals, and `tls renew` should not hang for minutes.
    // `force` renews whatever the CA says (it answers when to renew, which lego follows): lego 5 has a flag for it, lego 4 does
    // not, so there the threshold is out of reach and the answer of the CA is not asked.
    if (major >= 5)
      Seq("lego", "run") ++ options ++ renewDays.toSeq.flatMap(d => Seq("--renew-days", d.toString, "--no-random-sleep") ++ (if (force) Seq("--renew-force") else Nil))
    else
      Seq("lego") ++ options ++ renewDays.fold(Seq("run"))(d =>
        if (force) Seq("renew", "--days", "36500", "--ari-disable", "--no-random-sleep") else Seq("renew", "--days", d.toString, "--no-random-sleep")
      )
  }
}

/** Times, as the panel and the logs show them. */
object Iso {

  /** `2026-10-25T10:00:00Z` for milliseconds since the epoch (UTC). */
  def format(millis: Long): String = {
    val totalSeconds = Math.floorDiv(millis, 1000L)
    val days = Math.floorDiv(totalSeconds, 86400L)
    val secondOfDay = Math.floorMod(totalSeconds, 86400L).toInt
    // Civil date from days since 1970-01-01 (Howard Hinnant's algorithm).
    val z = days + 719468
    val era = Math.floorDiv(z, 146097L)
    val doe = (z - era * 146097).toInt
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val day = doy - (153 * mp + 2) / 5 + 1
    val month = if (mp < 10) mp + 3 else mp - 9
    val year = yoe + era * 400 + (if (month <= 2) 1 else 0)
    def p(n: Int, width: Int) = n.toString.reverse.padTo(width, '0').reverse
    s"${p(year.toInt, 4)}-${p(month, 2)}-${p(day, 2)}T${p(secondOfDay / 3600, 2)}:${p(secondOfDay / 60 % 60, 2)}:${p(secondOfDay % 60, 2)}Z"
  }
}

object Cert {
  private val Months = Seq("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
  private val NotAfter = "notAfter=([A-Za-z]{3}) +(\\d{1,2}) (\\d{2}):(\\d{2}):(\\d{2}) (\\d{4}) GMT".r

  /** Milliseconds since the epoch of the `notAfter=Jan 22 14:12:07 2027 GMT` that `openssl x509 -enddate` prints. */
  def parseNotAfter(output: String): Option[Long] =
    NotAfter.findFirstMatchIn(output).flatMap { m =>
      val month = Months.indexOf(m.group(1)) + 1
      if (month == 0) None
      else {
        val year = m.group(6).toInt
        val day = m.group(2).toInt
        // Days since 1970-01-01 of a civil date.
        val y = if (month <= 2) year - 1 else year
        val era = Math.floorDiv(y, 400)
        val yoe = y - era * 400
        val mp = if (month > 2) month - 3 else month + 9
        val doy = (153 * mp + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        val days = era.toLong * 146097 + doe - 719468
        Some(((days * 24 + m.group(3).toInt) * 60 + m.group(4).toInt) * 60000L + m.group(5).toInt * 1000L)
      }
    }
}

/** What happened to an app, one JSON object per line in `apps/<app>/events.jsonl`. */
object Events {
  def line(millis: Long, kind: String, release: Option[Int], message: String): String = {
    import Json.*
    obj(
      "at" -> Str(Iso.format(millis)),
      "type" -> Str(kind),
      "release" -> Json.option(release.map(r => Num(r.toLong))),
      "message" -> Str(message)
    ).render
  }

  /** The lines, oldest first, as a JSON array. */
  def array(lines: Seq[String]): String = lines.filter(_.startsWith("{")).mkString("[", ",", "]")
}

/** The access log of the edge: one tab separated line per request, parsed by the panel for its graphs. */
object AccessLog {
  val Format: String = "$host\t$status\t$request_time\t$body_bytes_sent"

  final case class Entry(host: String, status: Int, seconds: Double, bytes: Long)

  def parse(line: String): Option[Entry] =
    line.split('\t') match {
      case Array(host, status, time, bytes) =>
        for {
          s <- status.toIntOption
          t <- time.toDoubleOption
          b <- bytes.toLongOption
        } yield Entry(host.toLowerCase, s, t, b)
      case _ => None
    }
}

/** What the edge served to one app over a stretch of time, from its access log. */
final case class Aggregate(requests: Int, s2: Int, s3: Int, s4: Int, s5: Int, avgMs: Double, p95Ms: Double, bytes: Long)

object Metrics {
  def aggregate(entries: Seq[AccessLog.Entry]): Aggregate = {
    if (entries.isEmpty) Aggregate(0, 0, 0, 0, 0, 0, 0, 0)
    else {
      val millis = entries.map(_.seconds * 1000).sorted
      def count(low: Int) = entries.count(e => e.status >= low && e.status < low + 100)
      // Nearest rank.
      val p95 = millis(Math.max(0, Math.ceil(millis.length * 0.95).toInt - 1))
      Aggregate(entries.length, count(200), count(300), count(400), count(500), millis.sum / millis.length, p95, entries.map(_.bytes).sum)
    }
  }

  /** CPU use between two readings of the CPU time of a service, as a percentage of one core. None if it went back (restart). */
  def cpuPercent(previousNanos: Long, previousMs: Long, nowNanos: Long, nowMs: Long): Option[Double] =
    if (nowNanos < previousNanos || nowMs <= previousMs) None
    else Some((nowNanos - previousNanos).toDouble / ((nowMs - previousMs) * 1000000.0) * 100)

  /** One line of `metrics/<app>.jsonl`: the period that ends at `nowMs` and lasts `seconds`. */
  def point(nowMs: Long, seconds: Double, a: Aggregate, memoryBytes: Option[Long], cpuPct: Option[Double]): String = {
    import Json.*
    def number(value: Double) = Json.decimal(value)
    obj(
      "ms" -> Num(nowMs),
      "t" -> Str(Iso.format(nowMs)),
      "seconds" -> number(seconds),
      "requests" -> Json.num(a.requests),
      "s2" -> Json.num(a.s2),
      "s3" -> Json.num(a.s3),
      "s4" -> Json.num(a.s4),
      "s5" -> Json.num(a.s5),
      "avgMs" -> number(a.avgMs),
      "p95Ms" -> number(a.p95Ms),
      "bytes" -> Num(a.bytes),
      "memoryBytes" -> Json.option(memoryBytes.map(Num(_))),
      "cpuPct" -> Json.option(cpuPct.map(number))
    ).render
  }

  private val Ms = "\"ms\":(\\d+)".r

  /** The lines of the points at or after `minMs`, as a JSON array. */
  def since(lines: Seq[String], minMs: Long): String =
    lines
      .filter(l => l.startsWith("{") && Ms.findFirstMatchIn(l).exists(_.group(1).toLong >= minMs))
      .mkString("[", ",", "]")
}
