/*
 * Copyright 2020 Parsley Contributors <https://github.com/j-mie6/Parsley/graphs/contributors>
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package parsley.internal.deepembedding.backend

import parsley.internal.deepembedding.ContOps, ContOps.{suspend, ContAdapter}
import parsley.internal.machine.instructions

import StrictParsley.InstrBuffer

// Core Embedding
private [backend] abstract class Unary[A, B] extends StrictParsley[B] {
    protected val p: StrictParsley[A]
    def inlinable: Boolean = false
    // $COVERAGE-OFF$
    final override def pretty: String = pretty(p.pretty)
    protected def pretty(p: String): String
    // $COVERAGE-ON$
}

private [backend] abstract class ScopedUnary[A, B] extends Unary[A, B] {
    def instr: instructions.Instr
    def setup(label: Int): instructions.Instr
    def handlerLabel(state: CodeGenState): Int
    def instrNeedsLabel: Boolean
    final override def codeGen[M[_, +_]: ContOps, R](implicit instrs: InstrBuffer, state: CodeGenState): M[R, Unit] = {
        val handler = handlerLabel(state)
        instrs += setup(handler)
        suspend[M, R, Unit](p.codeGen) |> {
            if (instrNeedsLabel) instrs += new instructions.Label(handler)
            instrs += instr
        }
    }
}

private [backend] abstract class ScopedUnaryWithState[A, B](doesNotProduceHints: Boolean) extends ScopedUnary[A, B] {
    override def setup(label: Int): instructions.Instr = new instructions.PushHandlerAndState(label, doesNotProduceHints, doesNotProduceHints)
}
