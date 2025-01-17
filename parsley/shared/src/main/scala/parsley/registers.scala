/*
 * Copyright 2020 Parsley Contributors <https://github.com/j-mie6/Parsley/graphs/contributors>
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package parsley

import scala.collection.mutable

import parsley.Parsley.{empty, fresh, pure}
import parsley.combinator.{when, whileP}
import parsley.exceptions.UnfilledRegisterException
import parsley.implicits.zipped.Zipped2

import parsley.internal.deepembedding.{frontend, singletons}

/** This module contains all the functionality and operations for using and manipulating registers.
  *
  * These often have a role in performing context-sensitive parsing tasks, where a Turing-powerful
  * system is required. While `flatMap` is capable of such parsing, it is much less efficient
  * than the use of registers, though slightly more flexible. In particular, the `persist` combinator
  * enabled by `RegisterMethods` can serve as a drop-in replacement for `flatMap` in many scenarios.
  *
  * @since 2.2.0
  *
  * @groupprio reg 0
  * @groupname reg Registers
  * @groupdesc reg
  *     The `Reg` type is used to describe pieces of state that are threaded through a parser.
  *     The creation and basic combinators of registers are found within `Reg` and its companion
  *     object.
  *
  * @groupprio comb 5
  * @groupname comb Register-Based Combinators
  * @groupdesc comb
  *     Some combinators are made much more efficient in the presence of registers and they can
  *     be found here.
  *
  * @groupprio ext 10
  * @groupname ext Register Extension Combinators
  * @groupdesc ext
  *     These are implicit classes that, when in scope, enable additional combinators on
  *     parsers that interact with the register system in some way.
  */
object registers {
    /** This class is used to index registers within the mutable state.
      *
      * @note it is undefined behaviour to use a register in multiple different
      *       independent parsers. You should be careful to parameterise the
      *       registers in shared parsers and allocate fresh ones for each "top-level"
      *       parser you will run.
      * @since 2.2.0
      * @group reg
      *
      * @groupprio getters 0
      * @groupname getters Getters
      * @groupdesc getters
      *   These combinators allow for the retrieval of the stateful value of a register, and
      *   injecting it into the parsing context. Does not modify the contents of the register
      *   itself.
      *
      * @groupprio setters 5
      * @groupname setters Setters
      * @groupdesc setters
      *   These combinators directly update the value contained within a register. This new
      *   value can be provided directly or sourced from a parser.
      *
      * @groupprio mod 10
      * @groupname mod Modification
      * @groupdesc mod
      *   These combinators modify the value stored within a register by using a function.
      *   The function used can be provided directly or sourced from a parser.
      *
      * @groupprio local 15
      * @groupname local Local Modification
      * @groupdesc local
      *   These combinators allow for some form of local stateful modification. This means
      *   that any changes to the register may be reverted after the execution of the parser:
      *   this may be on the parsers success, but it could also involve the parsers failure.
      */
    class Reg[A] private [parsley] {
        /** This combinator injects the value stored in this register into a parser.
          *
          * Allows for the value stored in this register to be purely injected into
          * the parsing context. No input is consumed in this process, and it cannot fail.
          *
          * @example Get-Get Law: {{{
          * r.get *> r.get == r.get
          * r.get <~> r.get == r.get.map(x => (x, x))
          * }}}
          *
          * @return a parser that returns the value stored in this register.
          * @since 3.2.0
          * @group getters
          */
        def get: Parsley[A] = new Parsley(new singletons.Get(this))
        /** This combinator injects the value stored in this register into a parser after applying a function to it.
          *
          * Allows for the value stored in this register to be purely injected into
          * the parsing context but the function `f` is applied first. No input is
          * consumed in this process, and it cannot fail.
          *
          * @param f the function used to transform the value in this register.
          * @tparam B the desired result type.
          * @return the value stored in this register applied to `f`.
          * @since 3.2.0
          * @group getters
          */
        def gets[B](f: A => B): Parsley[B] = this.gets(pure(f))
        /** This combinator injects the value stored in this register into a parser after applying a function obtained from a parser to it.
          *
          * First, `pf` is parsed, producing the function `f` on success. Then,
          * the value stored in this register `x` is applied to the function `f`.
          * The combinator returns `f(x)`. Only `pf` is allowed to consume input.
          * If `pf` fails, the combinator fails, otherwise it will succeed.
          *
          * @param pf the parser that produces the function used to transform the value in this register.
          * @tparam B the desired result type.
          * @return the value stored in this register applied to a function generated from `pf`.
          * @since 3.2.0
          * @group getters
          */
        def gets[B](pf: Parsley[A => B]): Parsley[B] = pf <*> this.get
        /** This combinator stores a new value into this register.
          *
          * Without any other effect, the value `x` will be placed into this register.
          *
          * @example Put-Get Law: {{{
          * r.put(x) *> r.get == r.put(x) #> x
          * }}}
          *
          * @example Put-Put Law: {{{
          * r.put(x) *> r.put(y) == r.put(y)
          * }}}
          *
          * @param x the value to place in the register.
          * @since 3.2.0
          * @group setters
          */
        def put(x: A): Parsley[Unit] = this.put(pure(x))
        /** This combinator stores a new value into this register.
          *
          * First, parse `p` to obtain its result `x`. Then store `x` into
          * this register without any further effect. If `p` fails this
          * combinator fails.
          *
          * @example Get-Put Law: {{{
          * r.put(r.get) == unit
          * }}}
          *
          * @example Put-Put Law: {{{
          * // only when `q` does not inspect the value of `r`!
          * r.put(p) *> r.put(q) == p *> r.put(q)
          * }}}
          *
          * @param p the parser that produces the value to store in the register.
          * @since 3.2.0
          * @group setters
          */
        def put(p: Parsley[A]): Parsley[Unit] = new Parsley(new frontend.Put(this, p.internal))
        /** This combinator stores a new value into this register.
          *
          * First, parse `p` to obtain its result `x`. Then store `f(x)` into
          * this register without any further effect. If `p` fails this
          * combinator fails.
          *
          * Equivalent to {{{
          * this.put(p.map(f))
          * }}}
          *
          * @param p the parser that produces the value to store in the register.
          * @param f a function which adapts the result of `p` so that it can fit into this register.
          * @since 3.0.0
          * @group setters
          */
        def puts[B](p: Parsley[B], f: B => A): Parsley[Unit] = this.put(p.map(f))
        /** This combinator modifies the value stored in this register with a function.
          *
          * Without any other effect, get the value stored in this register, `x`, and
          * put back `f(x)`.
          *
          * Equivalent to {{{
          * this.put(this.gets(f))
          * }}}
          *
          * @param f the function used to modify this register's value.
          * @since 3.2.0
          * @group mod
          */
        def modify(f: A => A): Parsley[Unit] = new Parsley(new singletons.Modify(this, f))
        /** This combinator modifies the value stored in this register with a function.
          *
          * First, parse `pf` to obtain its result `f`. Then get the value stored in
          * this register, `x`, and put back `f(x)`. If `p` fails this combinator fails.
          *
          * Equivalent to {{{
          * this.put(this.gets(pf))
          * }}}
          *
          * @param pf  the parser that produces the function used to transform the value in this register.
          * @since 3.2.0
          * @group mod
          */
        def modify(pf: Parsley[A => A]): Parsley[Unit] = this.put(this.gets(pf))
        /** This combinator changed the value stored in this register for the duration of a given parser, resetting it afterwards.
          *
          * First get the current value in this register `x,,old,,`, then place `x` into this register
          * without any further effect. Then, parse `p`, producing result `y` on success. Finally,
          * put `x,,old,,` back into this register and return `y`. If `p` fails, the whole combinator fails and
          * the state is '''not restored'''.
          *
          * @example Put-Put Law: {{{
          * r.put(x) *> r.local(y)(p) == r.put(y) *> p <* r.put(x)
          * }}}
          *
          * @param x the value to place into this register.
          * @param p the parser to execute with the adjusted state.
          * @return the parser that performs `p` with the modified state `x`.
          * @since 3.2.0
          * @group local
          */
        def local[B](x: A)(p: Parsley[B]): Parsley[B] = this.local(pure(x))(p)
        /** This combinator changed the value stored in this register for the duration of a given parser, resetting it afterwards.
          *
          * First get the current value in this register `x,,old,,`, then parse `p` to get the result `x`, placing it into this register
          * without any further effect. Then, parse `q`, producing result `y` on success. Finally,
          * put `x,,old,,` back into this register and return `y`. If `p` or `q` fail, the whole combinator fails and
          * the state is '''not restored'''.
          *
          * @param p the parser whose return value is placed in this register.
          * @param q the parser to execute with the adjusted state.
          * @return the parser that performs `q` with the modified state.
          * @since 3.2.0
          * @group local
          */
        def local[B](p: Parsley[A])(q: =>Parsley[B]): Parsley[B] = new Parsley(new frontend.Local(this, p.internal, q.internal))
        /** This combinator changed the value stored in this register for the duration of a given parser, resetting it afterwards.
          *
          * First get the current value in this register `x,,old,,`, then place `f(x,,old,,)` into this register
          * without any further effect. Then, parse `p`, producing result `y` on success. Finally,
          * put `x,,old,,` back into this register and return `y`. If `p` fails, the whole combinator fails and
          * the state is '''not restored'''.
          *
          * @example Put-Put Law and Put-Get Law: {{{
          * r.put(x) *> r.local(f)(p) == r.put(f(x)) *> p <* r.put(x)
          * }}}
          *
          * @param f the function used to modify the value in this register.
          * @param p the parser to execute with the adjusted state.
          * @return the parser that performs `p` with the modified state.
          * @since 3.2.0
          * @group local
          */
        def local[B](f: A => A)(p: Parsley[B]): Parsley[B] = this.local(this.gets(f))(p)
        /** This combinator rolls-back any changes to this register made by a given parser if it fails.
          *
          * First get the current value in this register `x,,old,,`. Then parse `p`, if it succeeds,
          * producing `y`, then `y` is returned and this register retains its value post-`p`. Otherwise,
          * if `p` failed '''without consuming input''', `x,,old,,` is placed back into this register
          * and this combinator fails.
          *
          * This can be used in conjunction with local to make an ''almost'' unconditional state restore: {{{
          * // `r`'s state is always rolled back after `p` unless it fails having consumed input.
          * r.rollback(r.local(x)(p))
          * }}}
          *
          * @param p the parser to perform.
          * @return the result of the parser `p`, if any.
          * @since 3.2.0
          * @group local
          */
        def rollback[B](p: Parsley[B]): Parsley[B] = this.get.persist { x =>
            p <|> (this.put(x) *> empty)
        }

        private [this] var _v: Int = -1
        private [parsley] def addr: Int = {
            if (!allocated) throw new UnfilledRegisterException // scalastyle:ignore throw
            _v
        }
        private [parsley] def allocated: Boolean = _v != -1
        private [parsley] def allocate(v: Int): Unit = {
            assert(!allocated)
            this._v = v
        }
        // This must ONLY be used by CalleeSave in flatMap
        private [parsley] def deallocate(): Unit = _v = -1
        //override def toString: String = s"Reg(${if (allocated) addr else "unallocated"})"
    }
    /** This object allows for the construction of a register via its `make` function.
      * @group reg
      */
    object Reg {
        /** This function creates a new (global) register of a given type.
          *
          * The register created by this function is not allocated to any specific parser until it has been
          * used by a parser. It should not be used with multiple different parsers.
          *
          * @tparam A the type to be contained in this register during runtime
          * @return a new register which can contain the given type.
          * @note registers created in this manner ''must'' be initialised in the top-level parser and not
          *       inside a `flatMap`, as this may make them corrupt other registers. They should be used with
          *       caution. It is recommended to use `makeReg` and `fillReg` where possible.
          * @since 2.2.0
          */
        def make[A]: Reg[A] = new Reg
    }

    /** This class, when in scope, enables the use of combinators directly on parsers
      * that interact with the register system to store and persist results so they
      * can be used multiple times.
      *
      * @constructor This constructor should not be called manually, it is designed to be used via Scala's implicit resolution.
      * @param p the value that this class is enabling methods on.
      * @param con a conversion that allows values convertible to parsers to be used.
      * @group ext
      */
    implicit final class RegisterMethods[P, A](p: P)(implicit con: P => Parsley[A]) {
        /** This combinator fills a fresh register with the result of this parser, this
          * register is provided to the given function, which continues the parse.
          *
          * This allows for a more controlled way of creating registers during a parse,
          * without explicitly creating them with `Reg.make[A]` and using `put`. These
          * registers are intended to be fresh every time they are "created", in other
          * words, a recursive call with a `fillReg` call inside will modify a different
          * register each time.
          *
          * @example {{{
          * // this is an efficient implementation for persist.
          * def persist[B](f: Parsley[A] => Parsley[B]): Parsley[B] = this.fillReg(reg => f(reg.get))
          * }}}
          *
          * @param body a function to generate a parser that can interact with the freshly created register.
          * @since 4.0.0
          */
        def fillReg[B](body: Reg[A] => Parsley[B]): Parsley[B] = {
            val reg = Reg.make[A]
            new Parsley(new frontend.NewReg(reg, con(p).internal, body(reg).internal))
        }
        /** This combinator allows for the result of this parser to be used multiple times within a function,
          * without needing to reparse or recompute.
          *
          * Similar to `flatMap`, except it is much cheaper to do, at the cost of the restriction that the argument is `Parsley[A]` and not just `A`.
          *
          * @example {{{
          * // this is a reasonable implementation, though direct use of `branch` may be more efficent.
          * def filter(pred: A => Boolean): Parsley[A] = {
          *     this.persist(px => ifP(px.map(pred), px, empty))
          * }
          * }}}
          *
          * @param f a function to generate a new parser that can observe the result of this parser many times without reparsing.
          * @since 3.2.0
          */
        def persist[B](f: Parsley[A] => Parsley[B]): Parsley[B] = this.fillReg(reg => f(reg.get))
    }

    /** This class, when in scope, enables a method to create and fill a register with a
      * given value.
      *
      * @constructor This constructor should not be called manually, it is designed to be used via Scala's implicit resolution.
      * @param x the value to initialise a register with.
      * @group ext
      */
    implicit final class RegisterMaker[A](x: A) {
        /** This combinator fills a fresh register with the this value.
          *
          * This allows for a more controlled way of creating registers during a parse,
          * without explicitly creating them with `Reg.make[A]` and using `put`. These
          * registers are intended to be fresh every time they are "created", in other
          * words, a recursive call with a `makeReg` call inside will modify a different
          * register.
          *
          * @param body a function to generate a parser that can interact with the freshly created register.
          * @see [[parsley.registers.RegisterMethods.fillReg `fillReg`]] for a version that uses the result of a parser to fill the register instead.
          * @since 4.0.0
          */
        def makeReg[B](body: Reg[A] => Parsley[B]): Parsley[B] = pure(x).fillReg(body)
    }

    /** This combinator allows for the repeated execution of a parser `body` in a stateful loop, `body` will have access to the current value of the state.
      *
      * `forP_(init, cond, step)(body)` behaves much like a traditional for loop using `init`, `cond`, `step` and `body` as parsers
      * which control the loop itself. First, a register `r` is created and initialised with `init`. Then `cond` is parsed, producing
      * the function `pred`. If `r.gets(pred)` returns true, then `body` is parsed, then `r` is modified with the result of parsing `step`.
      * This repeats until `r.gets(pred)` returns false. This is useful for performing certain context sensitive tasks.
      *
      * @example the classic context sensitive grammar of `a^n^b^n^c^n^` can be matched using `forP_`:
      * {{{
      * val r = Reg.make[Int]
      *
      * r.put(0) *>
      * many('a' *> r.modify(_+1)) *>
      * forP_[Int](r.get, pure(_ != 0), pure(_ - 1)){_ => 'b'} *>
      * forP_[Int](r.get, pure(_ != 0), pure(_ - 1)){_ => 'c'}
      * }}}
      *
      * @param init the initial value of the induction variable.
      * @param cond the condition by which the loop terminates.
      * @param step the change in induction variable on each iteration.
      * @param body the body of the loop performed each iteration, which has access to the current value of the state.
      * @return a parser that initialises some state with `init` and then parses body until `cond` is true, modifying the state each iteration with `step`.
      * @see [[parsley.registers.forYieldP_ `forYieldP_`]] for a version that returns the results of each `body` parse.
      * @group comb
      */
    def forP_[A](init: Parsley[A], cond: =>Parsley[A => Boolean], step: =>Parsley[A => A])(body: Parsley[A] => Parsley[_]): Parsley[Unit] = {
        init.fillReg { reg =>
          lazy val _cond = reg.gets(cond)
          lazy val _step = reg.modify(step)
          when(_cond, whileP(body(reg.get) *> _step *> _cond))
        }
    }

    /** This combinator allows for the repeated execution of a parser `body` in a stateful loop, `body` will have access to the current value of the state.
      *
      * `forP_(init, cond, step)(body)` behaves much like a traditional for comprehension using `init`, `cond`, `step` and `body` as parsers
      * which control the loop itself. First, a register `r` is created and initialised with `init`. Then `cond` is parsed, producing
      * the function `pred`. If `r.gets(pred)` returns true, then `body` is parsed, then `r` is modified with the result of parsing `step`.
      * This repeats until `r.gets(pred)` returns false. This is useful for performing certain context sensitive tasks. Unlike `forP_` the
      * results of the body invokations are returned in a list.
      *
      * @example the classic context sensitive grammar of `a^n^b^n^c^n^` can be matched using `forP_`:
      * {{{
      * val r = Reg.make[Int]
      *
      * r.put(0) *>
      * many('a' *> r.modify(_+1)) *>
      * forYieldP_[Int](r.get, pure(_ != 0), pure(_ - 1)){_ => 'b'} *>
      * forYieldP_[Int](r.get, pure(_ != 0), pure(_ - 1)){_ => 'c'}
      * }}}
      *
      * This will return a list `n` `'c'` characters.
      *
      * @param init the initial value of the induction variable.
      * @param cond the condition by which the loop terminates.
      * @param step the change in induction variable on each iteration.
      * @param body the body of the loop performed each iteration, which has access to the current value of the state.
      * @return a parser that initialises some state with `init` and then parses body until `cond` is true, modifying the state each iteration with `step`.
      * @see [[parsley.registers.forP_ `forP_`]] for a version that ignores the results of the body.
      * @group comb
      */
    def forYieldP_[A, B](init: Parsley[A], cond: =>Parsley[A => Boolean], step: =>Parsley[A => A])(body: Parsley[A] => Parsley[B]): Parsley[List[B]] = {
        fresh(mutable.ListBuffer.empty[B]).fillReg { acc =>
            forP_(init, cond, step) { x =>
                acc.put((acc.get, body(x)).zipped(_ += _))
            } *> acc.gets(_.toList)
        }
    }

    /** This combinator allows for the repeated execution of a parser in a stateful loop.
      *
      * `forP(init, cond, step)(body)` behaves much like a traditional for loop using `init`, `cond`, `step` and `body` as parsers
      * which control the loop itself. First, a register `r` is created and initialised with `init`. Then `cond` is parsed, producing
      * the function `pred`. If `r.gets(pred)` returns true, then `body` is parsed, then `r` is modified with the result of parsing `step`.
      * This repeats until `r.gets(pred)` returns false. This is useful for performing certain context sensitive tasks.
      *
      * @example the classic context sensitive grammar of `a^n^b^n^c^n^` can be matched using `forP`:
      * {{{
      * val r = Reg.make[Int]
      *
      * r.put(0) *>
      * many('a' *> r.modify(_+1)) *>
      * forP[Int](r.get, pure(_ != 0), pure(_ - 1)){'b'} *>
      * forP[Int](r.get, pure(_ != 0), pure(_ - 1)){'c'}
      * }}}
      *
      * @param init the initial value of the induction variable.
      * @param cond the condition by which the loop terminates.
      * @param step the change in induction variable on each iteration.
      * @param body the body of the loop performed each iteration.
      * @return a parser that initialises some state with `init` and then parses body until `cond` is true, modifying the state each iteration with `step`.
      * @see [[parsley.registers.forYieldP `forYieldP`]] for a version that returns the results of each `body` parse.
      * @group comb
      */
    def forP[A](init: Parsley[A], cond: =>Parsley[A => Boolean], step: =>Parsley[A => A])(body: =>Parsley[_]): Parsley[Unit] = {
        lazy val _body = body
        forP_(init, cond, step) { _ =>
            _body
        }
    }

    /** This combinator allows for the repeated execution of a parser in a stateful loop.
      *
      * `forYieldP(init, cond, step)(body)` behaves much like a traditional for comprehension using `init`, `cond`, `step` and `body` as parsers
      * which control the loop itself. First, a register `r` is created and initialised with `init`. Then `cond` is parsed, producing
      * the function `pred`. If `r.gets(pred)` returns true, then `body` is parsed, then `r` is modified with the result of parsing `step`.
      * This repeats until `r.gets(pred)` returns false. This is useful for performing certain context sensitive tasks. Unlike `forP` the
      * results of the body invokations are returned in a list.
      *
      * @example the classic context sensitive grammar of `a^n^b^n^c^n^` can be matched using `forP`:
      * {{{
      * val r = Reg.make[Int]
      *
      * r.put(0) *>
      * many('a' *> r.modify(_+1)) *>
      * forYieldP[Int](r.get, pure(_ != 0), pure(_ - 1)){'b'} *>
      * forYieldP[Int](r.get, pure(_ != 0), pure(_ - 1)){'c'}
      * }}}
      *
      * This will return a list `n` `'c'` characters.
      *
      * @param init the initial value of the induction variable.
      * @param cond the condition by which the loop terminates.
      * @param step the change in induction variable on each iteration.
      * @param body the body of the loop performed each iteration.
      * @return a parser that initialises some state with `init` and then parses body until `cond` is true, modifying the state each iteration with `step`.
      *         The results of the iterations are returned in a list.
      * @see [[parsley.registers.forP `forP`]] for a version that ignores the results.
      * @group comb
      */
    def forYieldP[A, B](init: Parsley[A], cond: =>Parsley[A => Boolean], step: =>Parsley[A => A])(body: =>Parsley[B]): Parsley[List[B]] = {
        fresh(mutable.ListBuffer.empty[B]).fillReg { acc =>
            forP(init, cond, step) {
                acc.put((acc.get, body).zipped(_ += _))
            } *> acc.gets(_.toList)
        }
    }
}
