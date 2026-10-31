package panel

object Http {

  private def percentDecode(s: String): String = {
    val out = new java.io.ByteArrayOutputStream
    var i = 0
    while (i < s.length) {
      s.charAt(i) match {
        case '%' if i + 2 < s.length =>
          out.write(Integer.parseInt(s.substring(i + 1, i + 3), 16))
          i += 3
        case '+' =>
          out.write(' ')
          i += 1
        case c =>
          out.write(c.toString.getBytes("UTF-8"))
          i += 1
      }
    }
    new String(out.toByteArray, "UTF-8")
  }

  def decode(s: String): String =
    try percentDecode(s)
    catch { case _: NumberFormatException => s }

  def parseQuery(query: String): Map[String, String] =
    query.split('&').filter(_.nonEmpty).map { pair =>
      pair.split("=", 2) match {
        case Array(k, v) => decode(k) -> decode(v)
        case Array(k)    => decode(k) -> ""
      }
    }.toMap

  def cookie(header: Option[String], name: String): Option[String] =
    header.toSeq.flatMap(_.split(';')).map(_.trim).collectFirst {
      case c if c.startsWith(name + "=") => c.substring(name.length + 1)
    }

  /**
   * Whether a request that changes something comes from the panel's own page. A browser sends `Origin` on these
   * requests, and `Sec-Fetch-Site` too. Scripts like curl must send an `Origin` of the panel to get in.
   */
  def sameOrigin(origin: Option[String], host: Option[String], fetchSite: Option[String]): Boolean =
    origin match {
      case Some(o) =>
        val withoutScheme = o.indexOf("://") match { case -1 => o; case i => o.substring(i + 3) }
        host.exists(_.equalsIgnoreCase(withoutScheme))
      case None => fetchSite.exists(s => s == "same-origin" || s == "none")
    }

  def contentType(path: String): String =
    path.substring(path.lastIndexOf('.') + 1).toLowerCase match {
      case "html"        => "text/html; charset=utf-8"
      case "js" | "mjs"  => "text/javascript; charset=utf-8"
      case "css"         => "text/css; charset=utf-8"
      case "json"        => "application/json"
      case "svg"         => "image/svg+xml"
      case "png"         => "image/png"
      case "ico"         => "image/x-icon"
      case "woff2"       => "font/woff2"
      case "txt"         => "text/plain; charset=utf-8"
      case _             => "application/octet-stream"
    }
}
