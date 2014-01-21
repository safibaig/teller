/*
 * Happy Melly Teller
 * Copyright (C) 2013, Happy Melly http://www.happymelly.com
 *
 * This file is part of the Happy Melly Teller.
 *
 * Happy Melly Teller is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Happy Melly Teller is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Happy Melly Teller.  If not, see <http://www.gnu.org/licenses/>.
 *
 * If you have questions concerning this license or the applicable additional terms, you may contact
 * by email Sergey Kotlov, sergey.kotlov@happymelly.com or
 * in writing Happy Melly One, Handelsplein 37, Rotterdam, The Netherlands, 3071 PR
 */

package models

import org.joda.money.{ Money, CurrencyUnit }
import models.database.{ BookingEntries, Organisations, People, Accounts }
import play.api.db.slick.Config.driver.simple._
import play.api.db.slick.DB
import play.api.Play.current
import scala.math.BigDecimal.RoundingMode

/**
 * Represents a (financial) Account. An account has an `AccountHolder`, which is either a `Person`, `Organisation` or
 * the `Levy`. Accounts have a currency set upon activation and may only be deactivated when their balance is zero.
 */
case class Account(id: Option[Long] = None, organisationId: Option[Long] = None, personId: Option[Long] = None,
  currency: CurrencyUnit = CurrencyUnit.EUR, active: Boolean = false) {

  /** Resolves the holder for this account **/
  def accountHolder = (organisationId, personId) match {
    case (Some(o), None) ⇒ Organisation.find(o)
      .orElse(throw new IllegalStateException(s"Organisation with id $o (account holder for account ${id.getOrElse("(NEW)")}) does not exist"))
      .get
    case (None, Some(p)) ⇒ Person.find(p)
      .orElse(throw new IllegalStateException(s"Person with id $p (account holder for account ${id.getOrElse("(NEW)")}) does not exist"))
      .get
    case (None, None) ⇒ Levy
    case _ ⇒ throw new IllegalStateException(s"Account $id has both organisation and person for holder")
  }

  /**
   * Returns a set of people involved in this account, either as the direct account holder or organisation member,
   * where the board members are ‘participants’ of the levy account.
   */
  def participants: Set[Person] = accountHolder match {
    case organisation: Organisation ⇒ organisation.members.toSet
    case person: Person ⇒ Set(person)
    case Levy ⇒ Person.findBoardMembers
  }

  def balance: Money = Money.of(currency, Account.findBalance(id.get).bigDecimal)

  /**
   * Checks if the given user has permission to edit this account, including (de)activation:
   * - An account for a person may only be edited by that person
   * - An account for an organisation may only be edited by members of that organisation, or admins
   * - The Levy account may only be edited by admins
   */
  def editableBy(user: UserAccount) = {
    accountHolder match {
      case organisation: Organisation ⇒ user.admin || organisation.members.map(_.id.get).contains(user.personId)
      case person: Person ⇒ user.admin || person.id.get == user.personId
      case Levy ⇒ user.admin
    }
  }

  /**
   * Returns true if this account may be deleted.
   */
  lazy val deletable: Boolean = DB.withSession { implicit session: Session ⇒
    val hasBookingEntries = id.exists { accountId ⇒
      val query = Query(BookingEntries).filter(e ⇒ e.ownerId === accountId || e.fromId === accountId || e.toId === accountId)
      Query(query.exists).first
    }
    !active && !hasBookingEntries
  }

  /** Activates this account and sets the balance currency **/
  def activate(currency: CurrencyUnit): Unit = {
    if (active) throw new IllegalStateException("Cannot activate an already active account")
    assert(balance.isZero, "Inactive account's balance should be zero")
    updateStatus(active = true, currency)
  }

  /** Deactivates this account  **/
  def deactivate(): Unit = {
    if (!active) throw new IllegalStateException("Cannot deactivate an already inactive account")
    if (!balance.isZero) throw new IllegalStateException("Cannot deactivate with non-zero balance")
    updateStatus(active = false, currency)
  }

  def delete(): Unit = DB.withSession { implicit session: Session ⇒
    assert(deletable, "Attempt to delete account that is active or has booking entries")
    Accounts.where(_.id === id).mutate(_.delete())
  }

  def summary: AccountSummary = {
    AccountSummary(id.get, accountHolder.name, currency, active)
  }

  private def updateStatus(active: Boolean, currency: CurrencyUnit): Unit = DB.withSession { implicit session: Session ⇒
    val updateQuery = for { a ← Accounts if a.id === this.id } yield (a.id, a.active, a.currency)
    updateQuery.mutate(mutator ⇒ mutator.row = (mutator.row._1, active, currency))
  }
}

/**
 * Account summary for use in views.
 *
 * @param id Account ID
 * @param name Account holder name
 */
case class AccountSummary(id: Long, name: String, currency: CurrencyUnit, active: Boolean)

case class AccountSummaryWithBalance(id: Long, name: String, balance: Money)

object Account {

  def accountHolderName(firstName: Option[String], lastName: Option[String], organisation: Option[String]): String =
    (firstName, lastName, organisation) match {
      case (Some(first), Some(last), None) ⇒ first + " " + last
      case (None, None, Some(name)) ⇒ name
      case (None, None, None) ⇒ Levy.name
      case _ ⇒ throw new IllegalStateException(s"Invalid combination of first, last and organisation names ($firstName, $lastName, $organisation)")
    }

  def find(holder: AccountHolder): Account = DB.withSession { implicit session: Session ⇒
    val query = holder match {
      case o: Organisation ⇒ Query(Accounts).filter(_.organisationId === o.id)
      case p: Person ⇒ Query(Accounts).filter(_.personId === p.id)
      case Levy ⇒ Query(Accounts).filter(_.organisationId isNull).filter(_.personId isNull)
    }
    query.first()
  }

  def find(id: Long): Option[Account] = DB.withSession { implicit session: Session ⇒
    Query(Accounts).filter(_.id === id).firstOption()
  }

  /**
   * Returns a summary list of active accounts.
   */
  def findAllActive: List[AccountSummary] = DB.withSession { implicit session: Session ⇒
    val query = for {
      ((account, person), organisation) ← Accounts leftJoin
        People on (_.personId === _.id) leftJoin
        Organisations on (_._1.organisationId === _.id)
      if account.active === true
    } yield (account.id, account.currency, person.firstName.?, person.lastName.?, organisation.name.?, account.active)

    query.mapResult{
      case (id, currency, firstName, lastName, organisationName, active) ⇒
        AccountSummary(id, accountHolderName(firstName, lastName, organisationName), currency, active)
    }.list.sortBy(_.name.toLowerCase)
  }

  /**
   * Returns a summary list of active accounts with balances.
   */
  def findAllActiveWithBalance: List[AccountSummaryWithBalance] = DB.withSession { implicit session: Session ⇒

    // Sum booking entries’ credits and debits, grouped by account ID.
    val creditQuery = BookingEntries.groupBy(_.fromId).map {
      case (accountId, entry) ⇒
        accountId -> entry.map(_.fromAmount).sum
    }
    val debitQuery = BookingEntries.groupBy(_.toId).map {
      case (accountId, entry) ⇒
        accountId -> entry.map(_.toAmount).sum
    }

    // Transform each query result to a Map, for looking-up credit/debit by account ID.
    val credits = creditQuery.list.toMap.mapValues(_.getOrElse(BigDecimal(0)))
    val debits = debitQuery.list.toMap.mapValues(_.getOrElse(BigDecimal(0)))

    // Add the balances to the account summaries.
    findAllActive.map { account ⇒
      val accountDebit = debits.getOrElse(account.id, BigDecimal(0))
      val accountCredit = credits.getOrElse(account.id, BigDecimal(0))
      val balance = (accountDebit - accountCredit).setScale(2, RoundingMode.DOWN)
      AccountSummaryWithBalance(account.id, account.name, Money.of(account.currency, balance.bigDecimal))
    }
  }

  def findByPerson(personId: Long): Option[Account] = DB.withSession { implicit session ⇒
    Query(Accounts).filter(_.personId === personId).firstOption()
  }

  /**
   * Calculates the balance for a certain account by subtracting the total amount sent from the total amount received.
   * @param accountId The ID of the account to find the balance for.
   * @return The current balance for the account.
   */
  def findBalance(accountId: Long): BigDecimal = DB.withSession { implicit session: Session ⇒
    val creditQuery = for {
      entry ← BookingEntries.filtered if entry.fromId === accountId
    } yield entry.fromAmount

    val debitQuery = for {
      entry ← BookingEntries.filtered if entry.toId === accountId
    } yield entry.toAmount

    val credit = Query(creditQuery.sum).first.getOrElse(BigDecimal(0))
    val debit = Query(debitQuery.sum).first.getOrElse(BigDecimal(0))

    (debit - credit).setScale(2, RoundingMode.DOWN)
  }

}
