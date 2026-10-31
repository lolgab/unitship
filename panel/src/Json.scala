package panel

/** The JSON of the API: parsing what the browser sends and writing what it gets. */
enum J {
  case Obj(fields: Seq[(String, J)])
  case Arr(items: Seq[J])
  case Str(value: String)
  case Num(value: Double)
  case Bool(value: Boolean)
  case Null

  /** Already rendered JSON (what `unitship api` printed). */
  case Raw(json: String)

  def render: String = this match {
    case Obj(fields) => fields.map((k, v) => s"${J.quote(k)}:${v.render}").mkString("{", ",", "}")
    case Arr(items)  => items.map(_.render).mkString("[", ",", "]")
    case Str(value)  => J.quote(value)
    case Num(value)  => if (value == Math.rint(value) && Math.abs(value) < 1e15) value.toLong.toString else value.toString
    case Bool(value) => value.toString
    case Null        => "null"
    case Raw(json)   => json
  }

  def field(name: String): Option[J] = this match {
    case Obj(fields) => fields.collectFirst { case (`name`, v) => v }
    case _           => None
  }
  def string(name: String): Option[String] = field(name).collect { case Str(s) => s }
  def int(name: String): Option[Int] = field(name).collect { case Num(n) if n == Math.rint(n) && Math.abs(n) < 2e9 => n.toInt }
  def boolean(name: String): Option[Boolean] = field(name).collect { case Bool(b) => b }
  def strings(name: String): Option[Seq[String]] = field(name).collect { case Arr(items) if items.forall(_.isInstanceOf[Str]) => items.collect { case Str(s) => s } }
  def stringMap(name: String): Option[Seq[(String, String)]] =
    field(name).collect { case Obj(fs) if fs.forall(_._2.isInstanceOf[Str]) => fs.collect { case (k, Str(v)) => k -> v } }
}

object J {
  def obj(fields: (String, J)*): J = Obj(fields)

  def quote(s: String): String = {
    val out = new StringBuilder("\"")
    s.foreach {
      case '"'          => out ++= "\\\""
      case '\\'         => out ++= "\\\\"
      case '\n'         => out ++= "\\n"
      case '\r'         => out ++= "\\r"
      case '\t'         => out ++= "\\t"
      case c if c < ' ' => out ++= "\\u" + Integer.toHexString(c.toInt | 0x10000).substring(1)
      case c            => out += c
    }
    out += '"'
    out.toString
  }

  /** Parses a whole JSON document. */
  def parse(text: String): Either[String, J] = {
    val p = new Parser(text)
    try {
      val value = p.value()
      p.skipWhitespace()
      if (p.atEnd) Right(value) else Left(s"unexpected '${p.peek}' at ${p.position}")
    } catch { case e: ParseError => Left(e.getMessage) }
  }

  private final class ParseError(message: String) extends RuntimeException(message)

  private final class Parser(text: String) {
    private var i = 0
    private var depth = 0
    def position: Int = i
    def atEnd: Boolean = i >= text.length
    def peek: Char = text.charAt(i)
    private def fail(message: String): Nothing = throw new ParseError(s"$message at $i")

    def skipWhitespace(): Unit = while (i < text.length && " \t\r\n".indexOf(text.charAt(i)) >= 0) i += 1

    private def expect(c: Char): Unit = {
      if (atEnd || peek != c) fail(s"expected '$c'")
      i += 1
    }

    def value(): J = {
      skipWhitespace()
      if (atEnd) fail("unexpected end")
      peek match {
        case '{' => nested(obj())
        case '[' => nested(arr())
        case '"' => J.Str(string())
        case 't' => literal("true", J.Bool(true))
        case 'f' => literal("false", J.Bool(false))
        case 'n' => literal("null", J.Null)
        case _   => number()
      }
    }

    // A request must not be able to overflow the stack.
    private def nested(parse: => J): J = {
      depth += 1
      if (depth > 32) fail("too deeply nested")
      try parse
      finally depth -= 1
    }

    private def literal(word: String, result: J): J = {
      if (!text.startsWith(word, i)) fail(s"expected $word")
      i += word.length
      result
    }

    private def number(): J = {
      val start = i
      while (i < text.length && "+-0123456789.eE".indexOf(text.charAt(i)) >= 0) i += 1
      text.substring(start, i).toDoubleOption.map(J.Num(_)).getOrElse(fail("invalid number"))
    }

    private def string(): String = {
      expect('"')
      val out = new StringBuilder
      var done = false
      while (!done) {
        if (atEnd) fail("unterminated string")
        val c = text.charAt(i)
        i += 1
        c match {
          case '"' => done = true
          case '\\' =>
            if (atEnd) fail("unterminated string")
            val e = text.charAt(i)
            i += 1
            e match {
              case '"' | '\\' | '/' => out += e
              case 'n'              => out += '\n'
              case 'r'              => out += '\r'
              case 't'              => out += '\t'
              case 'b'              => out += '\b'
              case 'f'              => out += '\f'
              case 'u' =>
                if (i + 4 > text.length) fail("invalid escape")
                val code = Integer.parseInt(text.substring(i, i + 4), 16)
                out += code.toChar
                i += 4
              case _ => fail("invalid escape")
            }
          case c if c < ' ' => fail("control character in string")
          case c            => out += c
        }
      }
      out.toString
    }

    private def arr(): J = {
      expect('[')
      skipWhitespace()
      val items = Seq.newBuilder[J]
      if (!atEnd && peek == ']') i += 1
      else {
        var more = true
        while (more) {
          items += value()
          skipWhitespace()
          if (atEnd) fail("unterminated array")
          if (peek == ',') i += 1 else { expect(']'); more = false }
        }
      }
      J.Arr(items.result())
    }

    private def obj(): J = {
      expect('{')
      skipWhitespace()
      val fields = Seq.newBuilder[(String, J)]
      if (!atEnd && peek == '}') i += 1
      else {
        var more = true
        while (more) {
          skipWhitespace()
          val key = string()
          skipWhitespace()
          expect(':')
          fields += key -> value()
          skipWhitespace()
          if (atEnd) fail("unterminated object")
          if (peek == ',') i += 1 else { expect('}'); more = false }
        }
      }
      J.Obj(fields.result())
    }
  }
}
