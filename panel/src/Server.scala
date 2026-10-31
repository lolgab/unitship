package panel

import java.nio.file.*
import java.nio.file.attribute.PosixFilePermissions
import scala.util.control.NonFatal
import snunit.*

object Settings {
  val dataDir: Path = Paths.get(sys.env.getOrElse("DATA_DIR", "data")).toAbsolutePath
  /** The `unitship` of the host: the panel does nothing itself, it asks the command line tool. */
  val unitship: String = sys.env.getOrElse("UNITSHIP_BIN", "unitship")
  /** The session cookie is `Secure`, which a browser only accepts over HTTPS: set PANEL_INSECURE=1 to try the panel on http. */
  val secureCookies: Boolean = !sys.env.get("PANEL_INSECURE").contains("1")
  val sessionSeconds: Long = 7L * 24 * 3600
  val maxUpload: Long = 1L << 30
  val cookieName = "unitship_session"
}

/** What the panel keeps in its data directory: the password hash, the key of the sessions and the failed logins. */
object Store {
  import Settings.dataDir
  private def passwordFile = dataDir.resolve("password")
  private def secretFile = dataDir.resolve("secret")
  private def failuresFile = dataDir.resolve("failures")
  private def revokedFile = dataDir.resolve("sessions-revoked-at")
  private val privateFile = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))

  def passwordHash: Option[String] =
    if (Files.exists(passwordFile)) Some(new String(Files.readAllBytes(passwordFile), "UTF-8").trim) else None

  def setPassword(password: String): Unit = {
    Files.createDirectories(dataDir)
    val tmp = Files.createTempFile(dataDir, "password", ".tmp", privateFile)
    Files.write(tmp, (Passwords.hash(password) + "\n").getBytes("UTF-8"))
    Files.move(tmp, passwordFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    // A new password ends the sessions of the old one.
    revokeSessions(System.currentTimeMillis())
  }

  /** The moment (milliseconds) up to which sessions are not valid anymore. */
  def revokedAt(): Long =
    if (Files.exists(revokedFile)) new String(Files.readAllBytes(revokedFile), "UTF-8").trim.toLongOption.getOrElse(0L) else 0L

  def revokeSessions(nowMillis: Long): Unit = {
    Files.createDirectories(dataDir)
    Files.write(revokedFile, nowMillis.toString.getBytes("UTF-8"))
  }

  /** The key that signs the sessions, created the first time. Changing the password does not log anybody out: delete it for that. */
  def secret(): Array[Byte] = {
    def read = Encoding.unhex(new String(Files.readAllBytes(secretFile), "UTF-8").trim)
    if (Files.exists(secretFile)) read.getOrElse(throw new IllegalStateException(s"$secretFile is corrupt"))
    else {
      Files.createDirectories(dataDir)
      val fresh = Secure.randomBytes(32)
      try {
        Files.write(Files.createFile(secretFile, privateFile), Encoding.hex(fresh).getBytes("UTF-8"))
        fresh
      } catch {
        // Another process created it first: use that one.
        case _: FileAlreadyExistsException => read.getOrElse(fresh)
      }
    }
  }

  def failures(): Seq[Long] =
    if (Files.exists(failuresFile)) new String(Files.readAllBytes(failuresFile), "UTF-8").linesIterator.flatMap(_.trim.toLongOption).toSeq else Nil

  def recordFailure(nowSeconds: Long): Unit = {
    Files.createDirectories(dataDir)
    Files.write(failuresFile, s"$nowSeconds\n".getBytes("UTF-8"), StandardOpenOption.CREATE, StandardOpenOption.APPEND)
  }

  def clearFailures(): Unit = Files.deleteIfExists(failuresFile)
}

/** The `unitship` command line tool. */
object Cli {
  final case class Result(code: Int, output: String, error: String = "") { def ok: Boolean = code == 0 }

  private val MaxOutput = 256 * 1024

  /** Runs it with `args`. `merge` puts what it prints on the error output in the result too (for messages shown to the user);
    * for JSON only the standard output counts. */
  def run(args: Seq[String], merge: Boolean): Result =
    try {
      import scala.jdk.CollectionConverters.*
      val pb = new ProcessBuilder((Settings.unitship +: args).asJava)
      if (merge) pb.redirectErrorStream(true)
      val process = pb.start()
      process.getOutputStream.close()
      val bytes = process.getInputStream.readAllBytes()
      // What the tool prints on the error output is short: it is read after the rest.
      val errors = if (merge) Array.emptyByteArray else process.getErrorStream.readAllBytes()
      Result(
        process.waitFor(),
        new String(bytes.takeRight(MaxOutput), "UTF-8").trim,
        new String(errors.takeRight(MaxOutput), "UTF-8").trim
      )
    } catch { case e: java.io.IOException => Result(127, s"cannot run ${Settings.unitship}: ${e.getMessage}") }
}

final class Ctx(val req: Request) {
  val method: String = req.method
  val path: String = req.path
  val query: Map[String, String] = Http.parseQuery(req.query)
  private lazy val headerMap: Map[String, String] = {
    val b = Map.newBuilder[String, String]
    var i = 0
    while (i < req.headersLength) { b += req.headerNameUnsafe(i).toLowerCase -> req.headerValueUnsafe(i); i += 1 }
    b.result()
  }
  def header(name: String): Option[String] = headerMap.get(name.toLowerCase)
  def sameOrigin: Boolean = Http.sameOrigin(header("origin"), header("host"), header("sec-fetch-site"))
  def intQuery(name: String, default: Int, min: Int, max: Int): Int =
    query.get(name).flatMap(_.toIntOption).map(n => Math.max(min, Math.min(max, n))).getOrElse(default)
}

object Handler extends RequestHandler {
  private val AppName = "[a-z]([a-z0-9-]{0,30}[a-z0-9])?".r
  private val Domain = "[a-z0-9]([a-z0-9.-]{0,251}[a-z0-9])?".r
  private val EnvKey = "[A-Za-z_][A-Za-z0-9_]*".r

  private val SecurityHeaders = Seq(
    "X-Content-Type-Options" -> "nosniff",
    "X-Frame-Options" -> "DENY",
    "Referrer-Policy" -> "same-origin",
    "Content-Security-Policy" ->
      "default-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'"
  )

  private def now: Long = System.currentTimeMillis() / 1000
  private def nowMillis: Long = System.currentTimeMillis()

  def handleRequest(req: Request): Unit = {
    val c = new Ctx(req)
    try c.path.split('/').toList.filter(_.nonEmpty) match {
      case "api" :: rest => api(c, rest)
      case _             => static(c)
    } catch {
      case NonFatal(e) =>
        System.err.println(s"panel: ${c.method} ${c.path}: $e")
        error(c, StatusCode.InternalServerError, "internal error")
    }
  }

  // ---- responses

  private def respond(c: Ctx, status: StatusCode, contentType: String, body: Array[Byte], extra: Seq[(String, String)] = Nil): Unit =
    c.req.send(status, body, Headers((("Content-Type" -> contentType) +: (SecurityHeaders ++ extra))*))

  private def json(c: Ctx, status: StatusCode, value: J, extra: Seq[(String, String)] = Nil): Unit =
    respond(c, status, "application/json", value.render.getBytes("UTF-8"), Seq("Cache-Control" -> "no-store") ++ extra)

  private def error(c: Ctx, status: StatusCode, message: String, extra: Seq[(String, String)] = Nil): Unit =
    json(c, status, J.obj("error" -> J.Str(message)), extra)

  // ---- the command line tool

  /** The JSON that `unitship api ...` prints. */
  private def data(c: Ctx, args: String*): Unit = {
    val r = Cli.run(args, merge = false)
    if (r.ok) respond(c, StatusCode.OK, "application/json", r.output.getBytes("UTF-8"), Seq("Cache-Control" -> "no-store"))
    else {
      System.err.println(s"panel: unitship ${args.mkString(" ")} failed: ${r.error}")
      error(c, if (r.error.contains("unknown app")) StatusCode.NotFound else StatusCode.InternalServerError, "unitship failed")
    }
  }

  /** An action of `unitship`: the answer says whether it worked and what it printed. */
  private def act(c: Ctx, args: String*): Unit = actAll(c, Seq(args))

  private def actAll(c: Ctx, commands: Seq[Seq[String]]): Unit = {
    var failed = false
    val output = new StringBuilder
    commands.foreach { command =>
      if (!failed) {
        val r = Cli.run(command, merge = true)
        if (output.nonEmpty && r.output.nonEmpty) output ++= "\n"
        output ++= r.output
        failed = !r.ok
      }
    }
    json(c, if (failed) StatusCode(422) else StatusCode.OK, J.obj("ok" -> J.Bool(!failed), "output" -> J.Str(output.toString)))
  }

  // ---- authentication

  private def authenticated(c: Ctx): Boolean =
    Http.cookie(c.header("cookie"), Settings.cookieName).exists(Sessions.valid(Store.secret(), _, nowMillis, Store.revokedAt()))

  private def sessionCookie(token: String, maxAge: Long): (String, String) =
    "Set-Cookie" -> (s"${Settings.cookieName}=$token; Path=/; HttpOnly; SameSite=Strict; Max-Age=$maxAge" +
      (if (Settings.secureCookies) "; Secure" else ""))

  private def login(c: Ctx): Unit =
    body(c) match {
      case Left(message) => error(c, StatusCode.BadRequest, message)
      case Right(b) =>
        Store.passwordHash match {
          case None =>
            error(c, StatusCode(503), "no password is set: run `unitship exec <this app> set-password` on the server")
          case Some(stored) =>
            Lockout.retryAfter(Store.failures(), now) match {
              case Some(seconds) =>
                error(c, StatusCode.TooManyRequests, "too many failed logins, try again later", Seq("Retry-After" -> seconds.toString))
              case None =>
                if (Passwords.verify(b.string("password").getOrElse(""), stored)) {
                  Store.clearFailures()
                  val token = Sessions.issue(Store.secret(), nowMillis, Settings.sessionSeconds)
                  json(c, StatusCode.OK, J.obj("ok" -> J.Bool(true)), Seq(sessionCookie(token, Settings.sessionSeconds)))
                } else {
                  Store.recordFailure(now)
                  Thread.sleep(400)
                  error(c, StatusCode.Unauthorized, "wrong password")
                }
            }
        }
    }

  // ---- request bodies

  private def body(c: Ctx): Either[String, J] =
    if (!c.header("content-type").exists(_.toLowerCase.startsWith("application/json"))) Left("send application/json")
    else if (c.req.contentSize > 1024 * 1024) Left("the request is too large")
    else J.parse(new String(c.req.contentRaw(), "UTF-8"))

  private def withBody(c: Ctx)(f: J => Unit): Unit =
    body(c) match {
      case Left(message) => error(c, StatusCode.BadRequest, message)
      case Right(b)      => f(b)
    }

  // ---- routes

  private def api(c: Ctx, parts: List[String]): Unit = {
    val writes = c.method != "GET" && c.method != "HEAD"
    if (writes && !c.sameOrigin) error(c, StatusCode.Forbidden, "cross-origin request refused")
    else
      (c.method, parts) match {
        case ("GET", List("session")) =>
          json(c, StatusCode.OK, J.obj("authenticated" -> J.Bool(authenticated(c)), "configured" -> J.Bool(Store.passwordHash.nonEmpty)))
        case ("POST", List("login")) => login(c)
        case ("POST", List("logout")) =>
          // Only for whoever proves to be logged in: otherwise anybody could end the session of the administrator.
          if (authenticated(c)) Store.revokeSessions(nowMillis)
          json(c, StatusCode.OK, J.obj("ok" -> J.Bool(true)), Seq(sessionCookie("", 0)))
        case _ if !authenticated(c) => error(c, StatusCode.Unauthorized, "not logged in")
        case ("GET", List("status"))               => data(c, "api", "status")
        case ("GET", List("host", "metrics"))      => data(c, "api", "metrics", "_host", "-n", c.intQuery("minutes", 60, 1, 10080).toString)
        case ("GET", List("host", "logs"))         => logs(c, None)
        case ("POST", List("apps"))                => withBody(c)(b => withApp(c, b.string("name").getOrElse(""))(name => act(c, "create", name)))
        case (_, "apps" :: app :: rest) if !AppName.matches(app) => error(c, StatusCode.NotFound, "unknown app")
        case ("GET", List("apps", app))            => data(c, "api", "app", app)
        case ("GET", List("apps", app, "events"))  => data(c, "api", "events", app, "-n", c.intQuery("n", 100, 1, 500).toString)
        case ("GET", List("apps", app, "metrics")) => data(c, "api", "metrics", app, "-n", c.intQuery("minutes", 60, 1, 10080).toString)
        case ("GET", List("apps", app, "logs"))    => logs(c, Some(app))
        case ("DELETE", List("apps", app)) =>
          withBody(c)(b => if (b.string("confirm").contains(app)) act(c, "destroy", app, "--yes") else error(c, StatusCode.BadRequest, "confirm with the name of the app"))
        case ("POST", List("apps", app, "deploy"))   => deploy(c, app)
        case ("POST", List("apps", app, "rollback")) =>
          withBody(c)(b => actAll(c, Seq(Seq("rollback", app) ++ b.int("release").map(_.toString))))
        case ("POST", List("apps", app, action)) if Set("start", "stop", "restart")(action) => act(c, action, app)
        case ("PUT", List("apps", app, "env"))       => withBody(c)(env(c, app, _))
        case ("POST", List("apps", app, "domains"))  => withBody(c)(domains(c, app, _))
        case ("POST", List("apps", app, "tls")) =>
          withBody(c) { b =>
            val ds = b.strings("domains").getOrElse(Nil)
            if (!ds.forall(Domain.matches)) error(c, StatusCode.BadRequest, "invalid domain") else actAll(c, Seq(Seq("tls", app) ++ ds))
          }
        case _ => error(c, StatusCode.NotFound, "not found")
      }
  }

  private def withApp(c: Ctx, name: String)(f: String => Unit): Unit =
    if (AppName.matches(name)) f(name) else error(c, StatusCode.BadRequest, "invalid app name")

  private def logs(c: Ctx, app: Option[String]): Unit = {
    val n = c.intQuery("n", 300, 1, 2000).toString
    val r = Cli.run(app.fold(Seq("logs", "--edge", "-n", n))(a => Seq("logs", a, "-n", n)), merge = false)
    json(
      c,
      StatusCode.OK,
      J.obj(
        "lines" -> J.Arr(if (r.ok) r.output.linesIterator.map(J.Str(_)).toSeq else Nil),
        "error" -> (if (r.ok) J.Null else J.Str(if (r.error.nonEmpty) r.error else "no logs"))
      )
    )
  }

  private def env(c: Ctx, app: String, b: J): Unit = {
    val set = b.stringMap("set").getOrElse(Nil)
    val unset = b.strings("unset").getOrElse(Nil)
    val restart = b.boolean("restart").getOrElse(true)
    if (!(set.map(_._1) ++ unset).forall(EnvKey.matches)) error(c, StatusCode.BadRequest, "invalid variable name")
    else if (set.isEmpty && unset.isEmpty) error(c, StatusCode.BadRequest, "nothing to change")
    else {
      // One new release for both changes.
      val noRestart = Seq("--no-restart")
      val sets = if (set.nonEmpty) Seq(Seq("config", app, "set") ++ set.map((k, v) => s"$k=$v") ++ (if (unset.nonEmpty || !restart) noRestart else Nil)) else Nil
      val unsets = if (unset.nonEmpty) Seq(Seq("config", app, "unset") ++ unset ++ (if (!restart) noRestart else Nil)) else Nil
      actAll(c, sets ++ unsets)
    }
  }

  private def domains(c: Ctx, app: String, b: J): Unit = {
    val add = b.strings("add").getOrElse(Nil).map(_.toLowerCase)
    val remove = b.strings("remove").getOrElse(Nil).map(_.toLowerCase)
    if (!(add ++ remove).forall(Domain.matches)) error(c, StatusCode.BadRequest, "invalid domain")
    else if (add.isEmpty && remove.isEmpty) error(c, StatusCode.BadRequest, "nothing to change")
    else actAll(c, (if (add.nonEmpty) Seq(Seq("domains", app, "add") ++ add) else Nil) ++ (if (remove.nonEmpty) Seq(Seq("domains", app, "remove") ++ remove) else Nil))
  }

  /** The body is the executable: it is written to a file as it arrives, then deployed. */
  private def deploy(c: Ctx, app: String): Unit = {
    val size = c.req.contentSize
    if (size <= 0) error(c, StatusCode.BadRequest, "send the executable as the body, with a Content-Length")
    else if (size > Settings.maxUpload) error(c, StatusCode.PayloadTooLarge, "the executable is too large")
    else {
      val uploads = Settings.dataDir.resolve("uploads")
      Files.createDirectories(uploads)
      val file = Files.createTempFile(uploads, "deploy", ".bin")
      try {
        val out = Files.newOutputStream(file)
        try {
          val buffer = new Array[Byte](1 << 16)
          var remaining = size
          while (remaining > 0) {
            val n = c.req.readContent(buffer, 0, Math.min(buffer.length.toLong, remaining).toInt)
            if (n <= 0) throw new java.io.IOException("the upload ended early")
            out.write(buffer, 0, n)
            remaining -= n
          }
        } finally out.close()
        act(c, "deploy", app, file.toString)
      } finally Files.deleteIfExists(file)
    }
  }

  // ---- the page

  private lazy val assets: Map[String, Array[Byte]] =
    Assets.files.map((path, chunks) => path -> java.util.Base64.getDecoder.decode(chunks.mkString))

  private def static(c: Ctx): Unit =
    if (c.method != "GET" && c.method != "HEAD") error(c, StatusCode.MethodNotAllowed, "method not allowed")
    else {
      val requested = if (c.path == "/" || c.path.isEmpty) "/index.html" else c.path
      // A path with a dot is a file or nothing; any other path is a route of the page.
      val path = if (assets.contains(requested)) requested else if (!requested.contains('.')) "/index.html" else requested
      assets.get(path) match {
        case Some(gzip) =>
          val cache = if (path.startsWith("/assets/")) "public, max-age=31536000, immutable" else "no-cache"
          respond(c, StatusCode.OK, Http.contentType(path), gzip, Seq("Content-Encoding" -> "gzip", "Cache-Control" -> cache))
        case None if assets.isEmpty =>
          respond(c, StatusCode.NotFound, "text/plain; charset=utf-8", "The panel was built without its page: run panel/build.sh.\n".getBytes("UTF-8"))
        case None => error(c, StatusCode.NotFound, "not found")
      }
    }
}
