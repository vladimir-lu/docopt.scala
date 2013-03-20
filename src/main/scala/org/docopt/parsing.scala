package org.docopt.parsing

import scala.util.matching.Regex
import scala.util.matching.Regex.Match
import scala.{Option => SOption}

import org.docopt.matching.{PatternMatcher => PM}
import org.docopt.pattern._
import org.docopt.utils._

object PatternParser {
  type SeqOpt = List[Option]
  type Tokens = List[String]
  type ParseRet = (Tokens, SeqOpt, SeqPat)

  def parseValue(value: String): Value = {
    val intPattern = """^[0-9]+$""".r
    val doublePattern = """^[-+]?[0-9]*\.?[0-9]+([eE][-+]?[0-9]+)?$""".r
    val booleanPattern = """^(?i)(true|false)$""".r

    if (intPattern.findFirstIn(value).isDefined)
      IntValue(value.toInt)
    else if (doublePattern.findFirstIn(value).isDefined)
      DoubleValue(value.toDouble)
    else if (booleanPattern.findFirstIn(value).isDefined)
      BooleanValue(value.toBoolean)
    else
      StringValue(value)
  }

  def parseDefault(default: String): Value = {
    val defaultPattern = """\[(?i)default: (.*)\]""".r
    defaultPattern.findFirstMatchIn(default) match {
      case Some(defaultPattern(v)) => StringValue(v)
      case None => StringValue()
    }
  }

  def parseArgument(argument: String): SOption[Argument] =
    """(<\S*?>)""".r.findFirstIn(argument).map(name =>
      Argument(name, parseDefault(argument)))

  def parseOption(optionStr: String): SOption[Option] = {
    stringStrip(optionStr).split("  ").filter(_ != "") match {
      case Array(option, description) => {
        val option_ = option.replace(",", " ")
                            .replace("=", " ")
                            .split(" ")
                            .filter(_ != "")
        // extract short, long, argcount from options
        val (short, long, argcount) = option_.foldLeft(("","",0)) {
          case (tup, token) => {
            token match {
              case tok if tok startsWith "--" => (tup._1, tok, tup._3) // long option
              case tok if tok startsWith "-" => (tok, tup._2, tup._3) // short option
              case _ => (tup._1, tup._2, 1) // unnamed argument
            }
          }
        }
        Some(Option(short, long, argcount, if (argcount > 0)
          parseDefault(description)else BooleanValue(false)))
      }
      // TODO(fsaintjacques): remove this lazy hack
      case Array(option) => parseOption(option + "  EmptyDescription")
      // TODO(fsaintjacques): verify if we shouldn't throw an exception
      case _ => None
    }
  }

  def parsePattern(source: String,
                   options: SeqOpt,
                   argv: Boolean = false): Pattern = {
    val tokenizeRegex = new Regex("""([\[\]\(\)\|]|\.\.\.)""", "delim")
    val tokens = tokenStream(tokenizeRegex replaceAllIn
      (source, (m: Match) => " %s ".format(m.group("delim"))))
    val (tokens_, options_, results) = parseExpr(tokens, options, argv)
    if (tokens_.length > 0) throw new UnconsumedTokensException(tokens_)
    Required(results.toList)
  }

  // TODO(fsaintjacques): there is probably a more clean way of using
  // recursion
  def parseExpr(tokens: Tokens, options: SeqOpt, argv: Boolean): ParseRet = {
    def parseExprRecursive(toks: Tokens,
                           opts: SeqOpt,
                           res: SeqPat): ParseRet = toks match {
        case head :: tail if head == "|" =>
          val (toks_, opts_, seq_) = parseSeq(tail, opts, argv)
          parseExprRecursive(toks_, opts_, res ++ (if (seq_.length > 1) List(Required(seq_.toList)) else seq_))
        case _ =>
          (toks, opts, if (res.length > 1) List(Either(res.toList)) else res)
      }
    val ret@(tokens_, opts, seq) = parseSeq(tokens, options, argv)
    tokens_ match {
      case Nil => ret
      case head :: _ if head != "|" => ret
      case head :: tail =>
        parseExprRecursive(tokens_,
                           opts,
                           if (seq.length > 1) List(Required(seq.toList)) else seq)
    }
  }

  val reservedTokens = Set("]", ")", "|")
  def parseSeq(tokens: Tokens,
               options: SeqOpt,
               argv: Boolean,
               results: SeqPat = Nil): ParseRet = tokens match {
    case head :: _ if (!(reservedTokens contains head)) =>
      val (tokens_, options_, atoms) = parseAtom(tokens, options, argv)
      tokens_ match {
        case h :: tail if h == "..." =>
          parseSeq(tail, options_, argv, results ++ List(OneOrMore(atoms.toList)))
        case _ =>
          parseSeq(tokens_, options_, argv, results ++ atoms)
      }
    case _ => (tokens, options, results)
  }

  def parseAtom(tokens: Tokens,
                options: SeqOpt,
                argv: Boolean): ParseRet = tokens match {
    case head :: tail if (head == "(") =>
      val (tokens_, options_, expr) = parseExpr(tail, options, argv)
      tokens_ match {
        case h :: t if h == ")" =>
          (t, options_, List(Required(expr.toList)))
        case l:Tokens => throw new MissingEnclosureException(")")
      }
    case head :: tail if (head == "[") =>
      val (tokens_, options_, expr) = parseExpr(tail, options, argv)
      tokens_ match {
        case h :: t if h == "]" =>
          (t, options_, List(Optional(expr.toList)))
        case l:Tokens => throw new MissingEnclosureException("]")
      }
    case head :: tail if (head == "options") =>
      (tail, options, List(AnyOptions()))
    case head :: tail if (head != "--" && head.startsWith("--")) =>
      parseLongOption(tokens, options, argv)
    case head :: tail if (head != "-" && head != "--"  && head.startsWith("-")) =>
      parseShortOption(tokens, options, argv)
    case head :: tail if ((head.startsWith("<") && head.endsWith(">")) ||
                          head == head.toUpperCase) =>
      (tail, options, List(Argument(head)))
    case head :: tail =>
      (tail, options, List(Command(head)))
    }

  def parseLongOption(tokens: Tokens,
                      options: SeqOpt,
                      argv: Boolean = false): ParseRet = tokens match {
    case longToken :: tail => {
      val (long, eq, v) = longToken.split("=") match {
        case Array(once) => (once, "", "")
        case Array(l, v_) => (l, "=", v_)
        case _ => throw new UnparsableOptionException(longToken)
      }
      val sameName = (for (o <- options; if o.long == long) yield o).toList
      val similar = if (argv == true && sameName.isEmpty)
                      (for (o <- options; if o.long.startsWith(long)) yield o).toList
                    else sameName
      similar match {
        case Nil =>
          val argcount = if (eq == "=") 1 else 0
          val o = Option("", long, argcount)
          val o_ = Option("", long, argcount, if (argcount > 0) StringValue(v)
                                              else BooleanValue(true))
          (tail, options ++ List(o), List(if (argv == true) o_ else o))
        case head :: Nil => {
          val o@Option(oLong, oShort, oArgcount, oValue) = similar.head
          // TODO(fsaintjacques): remove var reference.
          var consumed = false
          var value = oArgcount match {
            case 0 if (v != "") =>
              throw new UnexpectedArgumentException(longToken)
            case 1 if (v == "" && tail.isEmpty) =>
              throw new MissingArgumentException(longToken)
            case 1 if (v == "") => {consumed = true; tail.head }
            case _ => v
          }
          val value_ = if (argv == true && value == "") BooleanValue(true)
                       else StringValue(value)
          val o_ = Option(oLong, oShort, oArgcount, value_)
          (if (consumed) tail.tail else tail, options,
           List(if (argv == true) o_ else o))
        }
        case head :: tail =>
          throw new RuntimeException("option %s is not unique: %s".format(long, options))
      }
    }
    case _ =>
      throw new RuntimeException("parseLongOption requires at least one token")
  }

  def parseShortOption(tokens: Tokens,
                       options: SeqOpt,
                       argv: Boolean = false): ParseRet = {
    def parseShortOptionRecursive(tok: Seq[Char],
                                  toks: Tokens,
                                  opts: SeqOpt,
                                  ret: List[Pattern]): ParseRet = tok match {
      case Nil => (toks, opts, ret.reverse)
      case Seq(t, ok@_*) => {
        val short = "-%s".format(t)
        val similar = (for (o <- options; if o.short == short) yield o).toList
        similar match {
          case Nil =>
            val o = Option(short, "", 0)
            val o_ = Option(short, "", 0, BooleanValue(true))
            parseShortOptionRecursive(ok, toks, opts ++ List(o), (if (argv == true) o_ else o) :: ret)
          case head :: Nil => {
            val Option(oShort, oLong, oArgcount, oValue) = similar.head
            var consumed = false
            var stop = false
            // TODO(fsaintjacques): remove var reference.
            val value:Value = oArgcount match {
              case 0 => BooleanValue(false)
              case 1 if (ok == "" && toks.isEmpty) => throw new MissingArgumentException(short)
              case 1 if (ok == "") => {consumed = true; StringValue(toks.head) }
              case 1 => {stop = true; StringValue(ok.mkString)}
            }
            val value_ = if (argv == true && value == BooleanValue(false)) BooleanValue(true) else value
            parseShortOptionRecursive(if (stop) "" else ok,
                                      if (consumed) toks.tail else toks,
                                      options, Option(oShort, oLong, oArgcount, value_) :: ret)
          }
          case h :: t => throw new UnparsableOptionException(short)
        }
      }
    }
    parseShortOptionRecursive(tokens.head.substring(1), tokens.tail, options, Nil)
  }

  def parseArgv(argv: String, options: SeqOpt, optionFirst:Boolean = false) =
    parseArgvRecursive(tokenStream(argv), options, optionFirst)

  private def parseArgvRecursive(tokens: Tokens,
                                 options: SeqOpt,
                                 optionFirst: Boolean,
                                 ret: List[Pattern] = Nil): SeqPat = tokens match {
    case Nil => ret.reverse
    case head :: _ if head == "--" =>
      parseArgvRecursive(Nil, options, optionFirst,
        (for (t <- tokens) yield Argument("", StringValue(t))).toList.reverse ++ ret)
    case head :: _ if head.startsWith("--") =>
      val (tokens_, options_, longs) = parseLongOption(tokens, options, true)
      parseArgvRecursive(tokens_, options_, optionFirst, longs.toList ++ ret)
    case head :: _ if head.startsWith("-") =>
      val (tokens_, options_, shorts) = parseShortOption(tokens, options, true)
      parseArgvRecursive(tokens_, options_, optionFirst, shorts.toList ++ ret)
    case head :: _ if optionFirst == true =>
      parseArgvRecursive(Nil, options, optionFirst,
        (for (t <- tokens) yield Argument("", StringValue(t))).toList.reverse ++ ret)
    case head :: tail =>
      parseArgvRecursive(tail, options, optionFirst,
        Argument("", StringValue(head)) :: ret)
  }

  def parseOptionDescriptions(doc: String): List[Option] =
    (for (optionMatch <- """\n[\t ]*(-\S+[^\n]*)""".r .findAllIn(doc).matchData;
          option <- parseOption(optionMatch.group(1))) yield option).toList


  private def tokenStream(source: String, split: Boolean = true): Tokens =
    source.split("\\s+").filter(_ != "").toList

  // keep only the Usage: part, remove everything after
  private def printableUsage(doc: String): String = {
    val usages = """((?i)usage:)""".r.split(doc)
    usages.length match {
      case n if n < 2 =>
        throw new DocoptLanguageException("'usage:' (case-insensitive) not found.")
      case n if n > 2 =>
        throw new DocoptLanguageException("More than one 'usage:' (case-insensitive).")
      case _ => stringStrip(("""\n\s*\n""".r.split(usages(1)))(0))
    }
  }

  private def formalUsage(doc: String): String = {
    val words = doc.split("\\s+")
    val programName = words(0)
    "( " +
    words.slice(1, words.size).map(x => if (x == programName) ") | (" else x).mkString(" ") +
    " )"
  }

  def docopt(doc: String,
             argv: String = "",
             help: Boolean = true,
             version: String = "",
             optionsFirst: Boolean = false) {
    val usage = formalUsage(printableUsage(doc))
    println("usage: " + usage)
    val options = parseOptionDescriptions(doc)
    println("options: " + options)
    val pattern = parsePattern(usage, options)
    println("pattern: " + pattern)
    val args = parseArgv(argv, options, optionsFirst)
    println("args: " + args)
    val patternOptions = Set(flattenPattern(Option("","")):_*)
    println("pattern_options: " + patternOptions)

    // TODO(fsaintjacques): take care of AnyOptions

    PM.matchPattern(fixPattern(pattern), args) match {
      case None => throw new DocoptExitException("pattern not matched")
      case Some((Nil, collected)) => println(collected)
      case Some((left, collected)) => throw new UnconsumedTokensException(left.map(_.toString))
  }
}
}
