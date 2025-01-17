# Effective Whitespace Parsing

@:callout(info)
This page is still being updated for the wiki port, so some things may be a bit broken or look a little strange.
@:@

Previously, in [Basics of Combinators] and [Building Expression Parsers],
we've seen parsers for languages that do not account for whitespace. In this page I'll discuss
the best practices for handling whitespace in your grammars.

## Defining whitespace readers
The first step in the correct handling of whitespace is to build the small parsers that
recognise the grammar itself.  The two concerns usually are spaces and comments. For
comments, the combinator `combinator.manyUntil` is _very_ useful. For example:

```scala
import parsley.Parsley, Parsley.attempt
import parsley.character.{whitespace, string, item, endOfLine}
import parsley.combinator.{manyUntil, skipMany}
import parsley.errors.combinator.ErrorMethods //for hide

def symbol(str: String): Parsley[String] = attempt(string(str))

val lineComment = symbol("//") *> manyUntil(item, endOfLine)
val multiComment = symbol("/*") *> manyUntil(item, symbol("*/"))
val comment = lineComment <|> multiComment

val skipWhitespace = skipMany(whitespace <|> comment).hide
```

Here, the `manyUntil` combinator is used to read up until the end of the comment. You may
notice the `hide` method having been called on `skipWhitespace`. This handy operation
hides the "expected" error message from a given parser. In other words, when we have a
parse error, it isn't particularly useful to see in the suggestions of what would have
worked that we could type some whitespace! Producing informative and tidy error messages,
however, is a more general topic for another post. Now that we have the `skipWhitespace`
parser we can start using it!

## Lexemes
Lexemes are indivisible chunks of the input, the sort usually produced by a lexer in a
classical setup. The `symbol` combinator I defined above forms part of this: it uses `attempt`
to make an indivisible string, either you read the entire thing or none of it. The next piece
of the puzzle is a combinator called `lexeme`, which should perform a parser and then always
read spaces after it:

```scala
import parsley.Parsley, Parsley.attempt

...

def lexeme[A](p: Parsley[A]): Parsley[A] = p <* skipWhitespace
def token[A](p: Parsley[A]): Parsley[A] = lexeme(attempt(p))

implicit def implicitSymbol(s: String): Parsley[String] = lexeme(symbol(s))
```

The `token` combinator is a more general form of `symbol`, that works for all parsers, handling
them atomically _and_ consuming whitespace after. Note that it's important to consume the whitespace
outside the scope of the `attempt`, otherwise malformed whitespace might cause backtracking for an
otherwise legal token!

With the `implicitSymbol` combinator, we can now treat all string literals as lexemes. This
can be very useful, but ideally this could be improved by also recognising whether or not the
provided string is a keyword, and if so, ensuring that it is not followed by another
alphabetical character. This is out of scope for this post, however.

Now let's take the example
from [Building Expression Parsers] and see what needs to change to finish up recognising whitespace.

```scala
import parsley.Parsley, Parsley.attempt
import parsley.character.{digit, whitespace, string, item, endOfLine}
import parsley.combinator.{manyUntil, skipMany}
import parsley.expr.{precedence, Ops, InfixL}
import parsley.errors.combinator.ErrorMethods //for hide

def symbol(str: String): Parsley[String] = attempt(string(str))

val lineComment = symbol("//") *> manyUntil(item, endOfLine)
val multiComment = symbol("/*") *> manyUntil(item, symbol("*/"))
val comment = lineComment <|> multiComment
val skipWhitespace = skipMany(whitespace <|> comment).hide

def lexeme[A](p: Parsley[A]): Parsley[A] = p <* skipWhitespace
def token[A](p: Parsley[A]): Parsley[A] = lexeme(attempt(p))

implicit def implicitSymbol(s: String): Parsley[String] = lexeme(symbol(s))

val number = token(digit.foldLeft1[BigInt](0)((n, d) => n * 10 + d.asDigit))

lazy val atom: Parsley[BigInt] = "(" *> expr <* ")" <|> number
lazy val expr = precedence[BigInt](atom)(
  Ops(InfixL)("*" #> (_ * _)),
  Ops(InfixL)("+" #> (_ + _), "-" #> (_ - _)))
```

Other than introducing our new infrastructure, I've changed the characters in the original
parser to strings: this is going to make them use our new `implicitLexeme` combinator! Notice
how I've also marked the _whole_ of `number` as a token: we don't want to read whitespace
between the digits, but instead after the entire number has been read, and a number should be entirely
atomic. Now that we've done this we can try running it on some input and see what happens:

```scala
expr.parse("5 + \n6 /*hello!*/ * 7")
// returns Success(47)

expr.parse(" 5 * (\n2 + 3)")
// returns Failure(..), talking about unexpected space at line 1 column 1
```

Ah, we've forgotten one last bit! The way we've set it up so far is that every lexeme reads
whitespace _after_ the token. This is nice and consistent and reduces any unnecessary extra
work reading whitespace before and after a token (which inevitably means whitespace will be
unnecessarily checked in between tokens _twice_). But this means we have to be careful to
read whitespace once at the very beginning of the parser. Using `skipWhitespace *> expr` as
our parser we run is the final step we need to make it all work. If we use `expr` in another
parser, however, we don't want to read the whitespace at the beginning in that case. It should
**only** be at the very start of the parser (so when `parse` is called).

## A Problem with Scope
The eagle-eyed reader might have spotted that there is a distinction between the string literals we
are using in the main parser and the `symbol`s we are using in the definitions of whitespace. Indeed,
because we are using an implicit that consumes whitespace, it would be inappropriate
to use it in the _definition_ of whitespace! If we were to pull in the `stringLift` implicit as we're
used to, then Scala will report and ambiguous implicit and we'll be stuck. It's a _much_ better idea
to limit the scope of these implicits, so we can be clear about which we mean where. To illustrate
what I mean, let's restructure the code a little for the parser and ensure we don't run into any issues.

```scala
import parsley.Parsley, Parsley.attempt
import parsley.character.{digit, whitespace, string, item, endOfLine}
import parsley.combinator.{manyUntil, skipMany, eof}
import parsley.expr.{precedence, Ops, InfixL}
import parsley.errors.combinator.ErrorMethods //for hide

object lexer {
    private def symbol(str: String): Parsley[String] = attempt(string(str))

    private val lineComment = symbol("//") *> manyUntil(item, endOfLine)
    private val multiComment = symbol("/*") *> manyUntil(item, symbol("*/"))
    private val comment = lineComment <|> multiComment
    private val skipWhitespace = skipMany(whitespace <|> comment).hide

    private def lexeme[A](p: Parsley[A]): Parsley[A] = p <* skipWhitespace
    private def token[A](p: Parsley[A]): Parsley[A] = lexeme(attempt(p))
    def fully[A](p: Parsley[A]): Parsley[A] = skipWhitespace *> p <* eof

    val number = token(digit.foldLeft1[BigInt](0)((n, d) => n * 10 + d.asDigit))

    object implicits {
        implicit def implicitSymbol(s: String): Parsley[String] = lexeme(symbol(s)) // or `lexeme(token(string(s)))
    }
}

object expressions {
    import lexer.implicits.implicitSymbol
    import lexer.{number, fully}

    private lazy val atom: Parsley[BigInt] = "(" *> expr <* ")" <|> number
    private lazy val expr = precedence[BigInt](atom)(
        Ops(InfixL)("*" #> (_ * _)),
        Ops(InfixL)("+" #> (_ + _), "-" #> (_ - _)))

    val parser = fully(expr)
}
```

In the above refactoring, I've introduced three distinct scopes: the `lexer`, the `lexer.implicits`
and the `expressions`. Within `lexer`, I've marked the internal parts as `private`, in particular
the `implicitSymbol` combinator that I've introduced to allow the lexer to use string literals in
the description of the tokens. By marking `implicitSymbol` as `private`, we ensure that it cannot be
accidentally used within `expressions`, where the main part of the parser is defined. In contrast,
the `implicits` object nested within `lexer` provides the ability for the `expressions` object to
hook into our whitespace sensitive string literal parsing (using `implicitToken`), and, but
enclosing it within the object, we prevent it being accidentally used inside the rest of the lexer
(without an explicit import, which we know would be bad!). This is a good general structure to adopt,
as it keeps the lexing code cleanly separated from the parser. If, for instance, you wanted to test
these internals, then you could leave them public, but I would advise adding a private to its
internal implicits at _all_ times (however, ScalaTest does have the ability to test `private` members!)
