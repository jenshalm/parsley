/*
 * Copyright 2020 Parsley Contributors <https://github.com/j-mie6/Parsley/graphs/contributors>
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package parsley.token.names

import parsley.Parsley, Parsley.{attempt, empty, pure}
import parsley.character.{satisfy, satisfyUtf16, stringOfMany, stringOfManyUtf16}
import parsley.errors.combinator.ErrorMethods
import parsley.implicits.zipped.Zipped2
import parsley.token.descriptions.{NameDesc, SymbolDesc}
import parsley.token.errors.ErrorConfig
import parsley.token.predicate.{Basic, CharPredicate, NotRequired, Unicode}

import parsley.internal.deepembedding.singletons

/** This class' implementation has been optimised for performance. If you've stumbled on
  * this file hoping to learn about how it works, this is the wrong place. The original,
  * unoptimised implementation is preserved for testing in the corresponding place in the
  * "test" folder. This is because a requirement of optimisation is that the semantics can
  * still be implemented by plain combinators. Go look there!
  */
private [token] class ConcreteNames(nameDesc: NameDesc, symbolDesc: SymbolDesc, err: ErrorConfig) extends Names {
    private def keyOrOp(startImpl: CharPredicate, letterImpl: CharPredicate, illegal: String => Boolean,
                        name: String, unexpectedIllegal: String => String) = {
        (startImpl, letterImpl) match {
            case (Basic(start), Basic(letter)) => new Parsley(new singletons.NonSpecific(name, unexpectedIllegal, start, letter, illegal))
            case _ => attempt {
                complete(startImpl, letterImpl).unexpectedWhen {
                    case x if illegal(x) => unexpectedIllegal(x)
                }
            }.label(name)
        }
    }
    private def trailer(impl: CharPredicate) = impl match {
        case Basic(letter) => stringOfMany(satisfy(letter))
        case Unicode(letter) => stringOfManyUtf16(satisfyUtf16(letter))
        case NotRequired => pure("")
    }
    private def complete(start: CharPredicate, letter: CharPredicate) = start match {
        case Basic(start) => (satisfy(start), trailer(letter)).zipped((c, cs) => s"$c$cs")
        case Unicode(start) => (satisfyUtf16(start), trailer(letter)).zipped { (c, cs) =>
            if (Character.isSupplementaryCodePoint(c)) s"${Character.highSurrogate(c)}${Character.lowSurrogate(c)}$cs"
            else s"${c.toChar}$cs"
        }
        case NotRequired => empty
    }
    override lazy val identifier: Parsley[String] =
        keyOrOp(nameDesc.identifierStart, nameDesc.identifierLetter, symbolDesc.isReservedName,
                err.labelNameIdentifier, err.unexpectedNameIllegalIdentifier)
    override def identifier(startChar: CharPredicate): Parsley[String] = attempt {
        err.filterNameIllFormedIdentifier.filter(identifier)(startChar.startsWith)
    }

    override lazy val userDefinedOperator: Parsley[String] =
        keyOrOp(nameDesc.operatorStart, nameDesc.operatorLetter, symbolDesc.isReservedOp, err.labelNameOperator, err.unexpectedNameIllegalOperator)

    def userDefinedOperator(startChar: CharPredicate, endChar: CharPredicate): Parsley[String] = attempt {
        err.filterNameIllFormedOperator.filter(userDefinedOperator)(x => startChar.startsWith(x) && endChar.endsWith(x))
    }
}
