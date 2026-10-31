//> using dep com.github.lolgab::snunit::0.13.0
package panel

import scala.concurrent.duration.*
import snunit.*
import snunit.config.*

// The panel of unitship: a web page to see and manage the apps of the host. It is an SNUnit application that unitship runs
// like any other. Everything it does goes through the `unitship` command line tool (UNITSHIP_BIN, UNITSHIP_HOME).
//
//   unitship create panel && unitship domains panel add panel.example.com
//   unitship deploy panel ./panel && unitship tls panel
//   unitship exec panel set-password

private def setPassword(): Unit = {
  def ask(prompt: String): String = {
    val script = s"""stty -echo 2>/dev/null; printf '%s' '$prompt' >&2; IFS= read -r p; stty echo 2>/dev/null; printf '\\n' >&2; printf '%s' "$$p" """
    val r = new ProcessBuilder("sh", "-c", script).redirectInput(ProcessBuilder.Redirect.INHERIT).redirectError(ProcessBuilder.Redirect.INHERIT).start()
    val bytes = r.getInputStream.readAllBytes()
    r.waitFor()
    new String(bytes, "UTF-8")
  }
  val first = ask("New password: ")
  if (first.length < 8) { System.err.println("panel: use at least 8 characters"); sys.exit(1) }
  if (ask("Again: ") != first) { System.err.println("panel: the passwords differ"); sys.exit(1) }
  Store.setPassword(first)
  println(s"panel: password saved in ${Settings.dataDir}")
}

private def serve(): Unit = {
  val address = s"${sys.env.getOrElse("BIND_ADDRESS", "127.0.0.1")}:${sys.env.getOrElse("PORT", "8081")}"
  if (Store.passwordHash.isEmpty)
    System.err.println("panel: no password is set yet: run `unitship exec <this app> set-password` on the server")
  SyncServerBuilder
    .setConfig(
      UnitConfig(
        listeners = Seq(Listener(address)),
        // A deploy or a certificate takes a while: several processes, so that the page keeps answering meanwhile.
        application = Application(
          processes = Some(Processes.Dynamic(max = 8, spare = 1)),
          limits = Some(Limits(timeout = Some(15.minutes)))
        ),
        http = HttpSettings(maxBodySize = Some(Settings.maxUpload), bodyReadTimeout = Some(5.minutes), sendTimeout = Some(5.minutes))
      )
    )
    .setRequestHandler(Handler)
    .build()
    .listen()
}

@main def panel(args: String*): Unit = args.toList match {
  case Nil                  => serve()
  case List("set-password") => setPassword()
  case _ =>
    System.err.println("usage: panel [set-password]")
    sys.exit(2)
}
