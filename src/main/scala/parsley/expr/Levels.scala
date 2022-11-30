/* SPDX-FileCopyrightText: © 2021 Parsley Contributors <https://github.com/j-mie6/Parsley/graphs/contributors>
 * SPDX-License-Identifier: BSD-3-Clause
 */
package parsley.expr

import parsley.Parsley

/** This class is the base type for precedence tables.
  *
  * For more complex expression parser types `Prec` can be used to
  * describe the precedence table whilst preserving the intermediate
  * structure between each level.
  *
  * The base of the table will always be an `Atoms`, and each layer
  * built on top of the last using either the `:+` or `+:` methods.
  *
  * @tparam A the type of structure produced by the list of levels.
  * @since 4.0.0
  * @group Table
  */
sealed abstract class Prec[+A] private [expr] {
    /** This method adds a new layer to this precedence table on the right, in a tightest-to-weakest ordering.
      *
      * This method associates to the left, so left-most applications are tighter binding (closer to the atoms)
      * than those to the right. It should not be mixed with `+:`, which would be confusing and less predictable.
      *
      * @tparam Aʹ a weakened version of the type generated by this table, to increase flexibility.
      * @tparam B the result type of the new table.
      * @param ops the operators that make up the new level on the table.
      * @return a new table that incorporates the operators and atoms in this table, along with extra `ops`.
      */
    final def :+[Aʹ >: A, B](ops: Ops[Aʹ, B]): Prec[B] = new Level(this, ops)
    /** This method adds a new layer to this precedence table on the left, in a weakest-to-tightest ordering.
      *
      * This method associates to the right (with this table on the right!), so right-most applications are
      * tighter binding (closer to the atoms) than those to the left. It should not be mixed with `:+`,
      * which would be confusing and less predictable.
      *
      * @tparam Aʹ a weakened version of the type generated by this table, to increase flexibility.
      * @tparam B the result type of the new table.
      * @param ops the operators that make up the new level on the table.
      * @return a new table that incorporates the operators and atoms in this table, along with extra `ops`.
      */
    final def +:[Aʹ >: A, B](ops: Ops[Aʹ, B]): Prec[B] = new Level(this, ops)
}
private [expr] case class Level[A, B](lvls: Prec[A], ops: Ops[A, B]) extends Prec[B]

/** This class is the base of a precedence table.
  *
  * Forms the base of a precedence table, requiring at least one atom to be
  * provided. This first atom will be parsed first.
  *
  * @tparam A the type of the atoms.
  * @param atom0 the first atom found at the root of the precedence table.
  * @param atoms any remaining atoms found at the root of the precedence table.
  * @since 4.0.0
  * @group Table
  */
case class Atoms[+A](atom0: Parsley[A], atoms: Parsley[A]*) extends Prec[A]
