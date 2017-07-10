package com.softwaremill.sttp

import java.net.{URI, URLEncoder}

object UriInterpolator {

  def interpolate(sc: StringContext, args: Any*): URI = {
    val strings = sc.parts.iterator
    val expressions = args.iterator
    var ub = UriBuilderStart.parseS(strings.next())

    while (strings.hasNext) {
      ub = ub.parseE(expressions.next())
      ub = ub.parseS(strings.next())
    }

    new URI(ub.build)
  }

  sealed trait UriBuilder {
    def parseS(s: String): UriBuilder
    def parseE(e: Any): UriBuilder
    def build: String

    protected def parseE_asEncodedS_skipNone(e: Any): UriBuilder = e match {
      case s: String => parseS(encode(s))
      case None => this
      case null => this
      case Some(x) => parseE(x)
      case x => parseS(encode(x.toString))
    }
  }

  val UriBuilderStart = Scheme("")

  case class Scheme(v: String) extends UriBuilder {

    override def parseS(s: String): UriBuilder = {
      val splitAtSchemeEnd = s.split("://", 2)
      splitAtSchemeEnd match {
        case Array(schemeFragment, rest) =>
          Authority(append(schemeFragment))
            .parseS(rest)

        case Array(x) =>
          if (!x.matches("[a-zA-Z0-9+\\.\\-]*")) {
            // anything else than the allowed characters in scheme suggest that there is no scheme
            // assuming whatever we parsed so far is part of authority, and parsing the rest
            // see https://stackoverflow.com/questions/3641722/valid-characters-for-uri-schemes
            Authority(Scheme(""), v).parseS(x)
          } else append(x)
      }
    }

    override def parseE(e: Any): UriBuilder = parseE_asEncodedS_skipNone(e)

    private def append(x: String): Scheme = Scheme(v + x)

    override def build: String = if (v.isEmpty) "" else v + "://"
  }

  case class Authority(s: Scheme, v: String = "") extends UriBuilder {

    override def parseS(s: String): UriBuilder = {
      // authority is terminated by /, ?, # or end of string (there might be
      // other /, ?, # later on e.g. in the query)
      // see https://tools.ietf.org/html/rfc3986#section-3.2
      s.split("[/\\?#]", 2) match {
        case Array(authorityFragment, rest) =>
          val splitOn = charAfterPrefix(authorityFragment, s)
          append(authorityFragment).next(splitOn, rest)
        case Array(x) => append(x)
      }
    }

    private def next(splitOn: Char, rest: String): UriBuilder =
      splitOn match {
        case '/' =>
          // prepending the leading slash as we want it preserved in the
          // output, if present
          Path(this).parseS("/" + rest)
        case '?' => Query(Path(this)).parseS(rest)
        case '#' => Fragment(Query(Path(this))).parseS(rest)
      }

    override def parseE(e: Any): UriBuilder = e match {
      case s: Seq[_] =>
        val newAuthority = s.map(_.toString).map(encode(_)).mkString(".")
        copy(v = v + newAuthority)
      case x => parseE_asEncodedS_skipNone(x)
    }

    override def build: String = {
      var vv = v
      // remove dangling "." which might occur due to optional authority
      // fragments
      while (vv.startsWith(".")) vv = vv.substring(1)
      while (vv.endsWith(".")) vv = vv.substring(0, vv.length - 1)

      s.build + vv
    }

    private def append(x: String): Authority = copy(v = v + x)
  }

  case class Path(a: Authority, fs: Vector[String] = Vector.empty)
      extends UriBuilder {

    override def parseS(s: String): UriBuilder = {
      // path is terminated by ?, # or end of string (there might be other
      // ?, # later on e.g. in the query)
      // see https://tools.ietf.org/html/rfc3986#section-3.3
      s.split("[\\?#]", 2) match {
        case Array(pathFragments, rest) =>
          val splitOn = charAfterPrefix(pathFragments, s)
          appendS(pathFragments).next(splitOn, rest)
        case Array(x) => appendS(x)
      }
    }

    private def next(splitOn: Char, rest: String): UriBuilder =
      splitOn match {
        case '?' => Query(this).parseS(rest)
        case '#' => Fragment(Query(this)).parseS(rest)
      }

    override def parseE(e: Any): UriBuilder = e match {
      case s: Seq[_] =>
        val newFragments = s.map(_.toString).map(encode(_)).map(Some(_))
        newFragments.foldLeft(this)(_.appendE(_))
      case s: String => appendE(Some(encode(s)))
      case None => appendE(None)
      case null => appendE(None)
      case Some(x) => parseE(x)
      case x => appendE(Some(encode(x.toString)))
    }

    override def build: String = {
      // if there is a trailing /, the last path fragment will be empty
      val v = if (fs.isEmpty) "" else "/" + fs.mkString("/")
      a.build + v
    }

    private def appendS(fragments: String): Path = {
      if (fragments.isEmpty) this
      else if (fragments.startsWith("/"))
        // avoiding an initial empty path fragment which would cause
        // initial // the build output
        copy(fs = fs ++ fragments.substring(1).split("/", -1))
      else
        copy(fs = fs ++ fragments.split("/", -1))
    }

    private def appendE(fragment: Option[String]): Path = fs.lastOption match {
      case Some("") =>
        // if the currently last path fragment is empty, replacing with the
        // expression value: corresponds to an interpolation of [anything]/$v
        copy(fs = fs.init ++ fragment)
      case _ => copy(fs = fs ++ fragment)
    }
  }

  type QueryFragment = (Option[String], Option[String])

  case class Query(p: Path, fs: Vector[QueryFragment] = Vector.empty)
      extends UriBuilder {

    override def parseS(s: String): UriBuilder = {
      s.split("#", 2) match {
        case Array(queryFragment, rest) =>
          Fragment(appendS(queryFragment)).parseS(rest)

        case Array(x) => appendS(x)
      }
    }

    override def parseE(e: Any): UriBuilder = e match {
      case m: Map[_, _] =>
        val flattenedM = m.flatMap {
          case (_, None) => None
          case (k, Some(v)) => Some((k, v))
          case (k, v) => Some((k, v))
        }
        val newFragments = flattenedM.map {
          case (k, v) =>
            (Some(encodeQuery(k)), Some(encodeQuery(v)))
        }
        copy(fs = fs ++ newFragments)

      case s: Seq[_] =>
        val flattenedS = s.flatMap {
          case (_, None) => None
          case (k, Some(v)) => Some((k, v))
          case None => None
          case Some(k) => Some(k)
          case x => Some(x)
        }
        val newFragments = flattenedS.map {
          case (k, v) =>
            (Some(encodeQuery(k)), Some(encodeQuery(v)))
          case x => (Some(encodeQuery(x)), None)
        }
        copy(fs = fs ++ newFragments)

      case s: String => appendE(Some(encodeQuery(s)))
      case None => appendE(None)
      case null => appendE(None)
      case Some(x) => parseE(x)
      case x => appendE(Some(encodeQuery(x.toString)))
    }

    override def build: String = {
      val fragments = fs.flatMap {
        case (None, None) => None
        case (Some(k), None) => Some(k)
        case (k, v) => Some(k.getOrElse("") + "=" + v.getOrElse(""))
      }

      val query = if (fragments.isEmpty) "" else "?" + fragments.mkString("&")

      p.build + query
    }

    private def appendS(queryFragment: String): Query = {

      val newVs = queryFragment.split("&").map { nv =>
        /*
         Representation of key-value pairs:
         - empty -> (None, None)
         - k=v   -> (Some(k), Some(v))
         - k=    -> (Some(k), Some(""))
         - k     -> (Some(k), None)
         - =     -> (None, Some(""))
         - =v    -> (None, Some(v))
         */
        if (nv.isEmpty) (None, None)
        else if (nv.startsWith("=")) (None, Some(nv.substring(1)))
        else
          nv.split("=", 2) match {
            case Array(n, v) => (Some(n), Some(v))
            case Array(n) => (Some(n), None)
          }
      }

      // it's possible that the current-last and new-first query fragments
      // are indeed two parts of a single fragment. Attempting to merge,
      // if possible
      val (currentInit, currentLastV) = fs.splitAt(fs.length - 1)
      val (newHeadV, newTail) = newVs.splitAt(1)

      val mergedOpt = for {
        currentLast <- currentLastV.headOption
        newHead <- newHeadV.headOption
      } yield {
        currentInit ++ merge(currentLast, newHead) ++ newTail
      }

      val combinedVs = mergedOpt match {
        case None => fs ++ newVs // either current or new fragments are empty
        case Some(merged) => merged
      }

      copy(fs = combinedVs)
    }

    private def merge(last: QueryFragment,
                      first: QueryFragment): Vector[QueryFragment] = {
      /*
       Only some combinations of fragments are possible. Part of them is
       already handled in `appendE` (specifically, any expressions of
       the form k=$v). Here we have to handle: $k=$v and $k=v.
       */
      (last, first) match {
        case ((Some(k), None), (None, Some(""))) =>
          Vector((Some(k), Some(""))) // k + =  => k=
        case ((Some(k), None), (None, Some(v))) =>
          Vector((Some(k), Some(v))) // k + =v => k=v
        case (x, y) => Vector(x, y)
      }
    }

    private def appendE(vo: Option[String]): Query = fs.lastOption match {
      case Some((Some(k), Some(""))) =>
        // k= + Some(v) -> k=v; k= + None -> remove parameter
        vo match {
          case None => copy(fs = fs.init)
          case Some(v) => copy(fs = fs.init :+ (Some(k), Some(v)))
        }
      case _ => copy(fs = fs :+ (vo, None))
    }
  }

  case class Fragment(q: Query, v: String = "") extends UriBuilder {
    override def parseS(s: String): UriBuilder = copy(v = v + s)

    override def parseE(e: Any): UriBuilder = parseE_asEncodedS_skipNone(e)

    override def build: String = q.build + (if (v.isEmpty) "" else s"#$v")
  }

  private def encode(s: Any): String = {
    // space is encoded as a +, which is only valid in the query;
    // in other contexts, it must be percent-encoded
    // see https://stackoverflow.com/questions/2678551/when-to-encode-space-to-plus-or-20
    URLEncoder.encode(String.valueOf(s), "UTF-8").replaceAll("\\+", "%20")
  }

  private def encodeQuery(s: Any): String =
    URLEncoder.encode(String.valueOf(s), "UTF-8")

  private def charAfterPrefix(prefix: String, whole: String): Char = {
    val pl = prefix.length
    whole.substring(pl, pl + 1).charAt(0)
  }
}
