/*
 * Happy Melly Teller
 * Copyright (C) 2013 - 2015, Happy Melly http://www.happymelly.com
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
 * If you have questions concerning this license or the applicable additional
 * terms, you may contact by email Sergey Kotlov, sergey.kotlov@happymelly.com or
 * in writing Happy Melly One, Handelsplein 37, Rotterdam, The Netherlands, 3071 PR
 */
package controllers

import models.UserRole.Role._
import models._
import models.payment.{Payment, PaymentException, RequestException}
import models.service.Services
import org.joda.money.CurrencyUnit._
import org.joda.money.Money
import org.joda.time.DateTime
import play.api.Play.current
import play.api.cache.Cache
import play.api.data.Form
import play.api.data.Forms._
import play.api.i18n.{I18nSupport, Messages}
import play.api.libs.json.Json
import play.api.mvc._
import play.api.{Logger, Play}
import securesocial.controllers.BaseRegistration
import securesocial.core.authenticator.CookieAuthenticator
import securesocial.core.providers.UsernamePasswordProvider
import securesocial.core.providers.utils.PasswordValidator
import securesocial.core.services.SaveMode
import securesocial.core.utils._
import securesocial.core.{AuthenticationMethod, BasicProfile, SecureSocial}
import services.TellerRuntimeEnvironment
import views.Countries

import scala.concurrent.{Await, Future}


/**
 * Contains registration data required to create a person object
 * @param firstName First name
 * @param lastName Last name
 * @param email Email address
 * @param country Country where the person lives
 */
case class UserData(firstName: String,
  lastName: String,
  email: String,
  country: String,
  org: Boolean = false,
  orgData: OrgData = OrgData("", ""))

/**
 * Contains registration data required to create an organization
 * @param name Name
 * @param country Country where the organization is registered
 */
case class OrgData(name: String, country: String)

case class AuthenticationInfo(email: String, password: String)

/**
 * -v
 * Contains actions for a registration process
 */
class Registration @javax.inject.Inject() (override implicit val env: TellerRuntimeEnvironment)
    extends PasswordIdentities
    with Enrollment
    with Security
    with Activities
    with Services
    with I18nSupport {

  val REGISTRATION_COOKIE = "registration"

  class ValidationException(msg: String) extends RuntimeException(msg) {}

  private def userForm = Form(mapping(
    "firstName" -> nonEmptyText,
    "lastName" -> nonEmptyText,
    "email" -> play.api.data.Forms.email.verifying("Email address is already in use", { suppliedEmail =>
      import scala.concurrent.duration._
      Await.result(Future.successful(identityService.checkEmail(suppliedEmail)), 10.seconds)
    }),
    "country" -> nonEmptyText.verifying(
      "error.unknown_country",
      (value: String) ⇒ Countries.all.exists(_._1 == value)),
    "org" -> ignored(false),
    "orgData" -> ignored(OrgData("", "")))(UserData.apply)(UserData.unapply))

  private def orgForm = Form(mapping(
    "name" -> nonEmptyText,
    "country" -> nonEmptyText.verifying(
      "error.unknown_country",
      (value: String) ⇒ Countries.all.exists(_._1 == value)))(OrgData.apply)(OrgData.unapply))

  private def passwordForm = Form[AuthenticationInfo](
    mapping(
      "email" -> play.api.data.Forms.email.verifying("Email address is already in use", { suppliedEmail =>
        import scala.concurrent.duration._
        Await.result(Future.successful(identityService.checkEmail(suppliedEmail)), 10.seconds)
      }),
      "password" ->
        tuple(
          "password1" -> nonEmptyText.verifying(PasswordValidator.constraint),
          "password2" -> nonEmptyText
        ).verifying(Messages(BaseRegistration.PasswordsDoNotMatch), passwords => passwords._1 == passwords._2)

    )((email, password) => AuthenticationInfo(email, password._1))((info: AuthenticationInfo) => Some((info.email, ("", "")))))

  /**
   * The authentication flow for all providers starts here.
   *
   * @param provider The id of the provider that needs to handle the call
   */
  def authenticate(provider: String) = Action { implicit request ⇒
    val session = request.session -
      SecureSocial.OriginalUrlKey +
      (SecureSocial.OriginalUrlKey -> routes.Registration.step2().url)
    val route = env.routes.authenticationUrl(provider)
    Redirect(route).withSession(session)
  }

  def authenticateByEmail() = Action.async { implicit request =>
    passwordForm.bindFromRequest().fold(
      errors => Future.successful(BadRequest(views.html.v2.registration.step1(errors))),
      info => {
        val newUser = BasicProfile(
          UsernamePasswordProvider.UsernamePassword,
          info.email,
          None,
          None,
          None,
          Some(info.email),
          None,
          AuthenticationMethod.UserPassword,
          passwordInfo = Some(env.currentHasher.hash(info.password))
        )
        env.userService.save(newUser, SaveMode.SignUp).map { user =>
          env.authenticatorService.find(CookieAuthenticator.Id).map {
            _.fromUser(user).flatMap { authenticator =>
              Redirect(routes.Registration.step2().url).startingAuthenticator(authenticator)
            }
          } getOrElse {
            Logger.error(s"[securesocial] There isn't CookieAuthenticator registered in the RuntimeEnvironment")
            Future.successful(Redirect(routes.LoginPage.login()).flashing("error" -> Messages("There was an error signing you up")))
          }
        }.flatMap(f => f)
      }
    )
  }

  /**
   * Renders welcome page for new users
   */
  def welcome = Action { implicit request ⇒
    Ok(views.html.v2.registration.welcome())
  }

  /**
   * Renders step 1 page of the registration process
   *
   * @param org Defines if a new Supporter is an organization or a person
   */
  def step1(org: Boolean = false) = Action { implicit request ⇒
    val cookie = Cookie(REGISTRATION_COOKIE, "org")
    val discardingCookie = DiscardingCookie(REGISTRATION_COOKIE)
    if (org)
      Ok(views.html.v2.registration.step1(passwordForm)).withCookies(cookie)
    else
      Ok(views.html.v2.registration.step1(passwordForm)).discardingCookies(discardingCookie)
  }

  /**
   * Renders step 2 page of the registration process
   * @return
   */
  def step2 = SecuredRestrictedAction(Unregistered) { implicit request ⇒ implicit handler ⇒ implicit user ⇒
    redirectViewer {
      val form = userForm.bind(Map(("firstName", user.person.firstName),
        ("lastName", user.person.lastName),
        ("email", user.person.email)))
      Ok(views.html.v2.registration.step2(user, form))
    }
  }

  /**
   * Renders step 3 page of the registration process
   */
  def step3 = SecuredRestrictedAction(Unregistered) { implicit request ⇒
    implicit handler ⇒ implicit user ⇒
      redirectViewer {
        Ok(views.html.v2.registration.step3(user, orgForm))
      }
  }

  /**
   * Saves new person to cache
   */
  def savePerson = SecuredRestrictedAction(Unregistered) { implicit request ⇒
    implicit handler ⇒ implicit user ⇒
      redirectViewer {
        userForm.bindFromRequest.fold(
          errForm ⇒ BadRequest(views.html.v2.registration.step2(user, errForm)),
          data ⇒ {
            val id = personCacheId(user.id)
            Cache.set(id, data, 900)
            val paymentUrl = routes.Registration.payment().url
            val url: String = request.cookies.get(REGISTRATION_COOKIE) map { x ⇒
              if (x.value == "org")
                routes.Registration.step3().url
              else
                paymentUrl
            } getOrElse paymentUrl
            Redirect(url)
          })
      }
  }

  /**
   * Saves new org to cache
   */
  def saveOrg = SecuredRestrictedAction(Unregistered) { implicit request ⇒
    implicit handler ⇒ implicit user ⇒
      redirectViewer {
        checkPersonData { implicit userData ⇒
          orgForm.bindFromRequest.fold(
            errForm ⇒ BadRequest(views.html.v2.registration.step3(user, errForm)),
            data ⇒ {
              val id = personCacheId(user.id)
              Cache.set(id, userData.copy(org = true, orgData = data), 900)
              Redirect(routes.Registration.payment())
            })
        }
      }
  }

  /**
   * Renders Payment page of the registration process
   */
  def payment = SecuredRestrictedAction(Unregistered) { implicit request ⇒
    implicit handler ⇒ implicit user ⇒
      redirectViewer {
        checkPersonData { implicit userData ⇒
          val publicKey = Play.configuration.getString("stripe.public_key").get
          val person = unregisteredPerson(userData, user)
          val country = if (userData.org) userData.orgData.country else userData.country
          val org = if (userData.org)
            Some(Organisation(userData.orgData.name, userData.orgData.country))
          else
            None
          val fee = Payment.countryBasedFees(country)
          Ok(views.html.v2.registration.payment(paymentForm, person, publicKey, fee, org))
        }
      }
  }

  /**
   * Makes a transaction and creates all objects
   */
  def charge = SecuredRestrictedAction(Unregistered) { implicit request ⇒
    implicit handler ⇒ implicit user ⇒
      redirectViewer {
        Cache.getAs[UserData](personCacheId(user.id)) map { userData ⇒
          val person = unregisteredPerson(userData, user).insert
          val org = if (userData.org) {
            val profile = SocialProfile(0, ProfileType.Organisation)
            val view = orgService.insert(OrgView(unregisteredOrg(userData), profile))
            Some(view.org)
          } else {
            None
          }

          paymentForm.bindFromRequest.fold(
            hasError ⇒
              BadRequest(Json.obj("message" -> Messages("error.payment.unexpected_error"))),
            data ⇒ {
              try {
                if (data.fee < Payment.countryBasedFees(person.address.countryCode)._1) {
                  throw new ValidationException("error.payment.minimum_fee")
                }
                val customerId = subscribe(person, org, data)
                org map { x ⇒
                  orgService.update(x.copy(customerId = Some(customerId), active = true))
                  person.copy(active = true).update
                  person.addRelation(x.id.get)
                } getOrElse {
                  person.copy(customerId = Some(customerId), active = true).update
                }
                val fee = Money.of(EUR, data.fee)
                val member = org map { x ⇒
                  x.becomeMember(funder = false, fee, person.id.get)
                } getOrElse {
                  person.becomeMember(funder = false, fee)
                }
                createUserAccount(user.id, user.providerId, person, member)
                notify(person, org, member)
                subscribe(person, member)

                activity(member, person).becameSupporter.insert()

                val orgId = org map (_.id) getOrElse None
                Ok(Json.obj("redirect" -> routes.Registration.congratulations(orgId).url))
              } catch {
                case e: PaymentException ⇒
                  val error = e.code match {
                    case "card_declined" ⇒ "error.payment.card_declined"
                    case "incorrect_cvc" ⇒ "error.payment.incorrect_cvc"
                    case "expired_card" ⇒ "error.payment.expired_card"
                    case "processing_error" ⇒ "error.payment.processing_error"
                    case _ ⇒ "error.payment.unexpected_error"
                  }
                  clean(person, org)
                  BadRequest(Json.obj("message" -> Messages(error)))
                case e: RequestException ⇒
                  clean(person, org)
                  e.log.foreach(Logger.error(_))
                  BadRequest(Json.obj("message" -> Messages(e.getMessage)))
                case e: ValidationException ⇒
                  clean(person, org)
                  BadRequest(Json.obj("message" -> Messages(e.getMessage)))
              }
            })
        } getOrElse {
          Ok(Json.obj("redirect" -> routes.Registration.step2().url))
        }
      }
  }

  /**
   * Renders congratulations screen
   *
   * @param orgId Organisation identifier
   */
  def congratulations(orgId: Option[Long] = None) = Action { implicit request ⇒
    orgId map { id ⇒
      Ok(views.html.v2.registration.congratulations(routes.Organisations.details(id).url, true))
    } getOrElse {
      Ok(views.html.v2.registration.congratulations(routes.Dashboard.profile().url, false))
    }
  }

  /**
    * Returns new user account for the given person
    * @param person Person
    */
  protected def account(person: Person): UserAccount =
    UserAccount(None, person.identifier, true,
      person.socialProfile.twitterHandle,
      person.socialProfile.facebookUrl,
      person.socialProfile.linkedInUrl,
      person.socialProfile.googlePlusUrl,
      member = true, registered = true)

  /**
    * Adds new person as a member and updates cached object
   *
   * By updating cached object we give the user a full access to the system
   *  without relogging
   *
   * @param id Identity object
   * @param person Person
   */
  protected def createUserAccount(id: String,
                                  providerId: String,
                                  person: Person,
                                  member: Member)(implicit request: RequestHeader) = {
    val inserted = userAccountService.insert(account(person))
    if (providerId == UsernamePasswordProvider.UsernamePassword) {
      Logger.info(s"End of registration of a user with ${id} id")
      registeringUserService.delete(id, providerId)
      identityService.findByEmail(id) map { identity =>
        identityService.update(identity.copy(userId = person.id,
          firstName = Some(person.firstName),
          lastName = Some(person.lastName)))
      } getOrElse {
        Logger.error(s"$id wasn't found in PasswordIdentity table on the final stage of registration")
        throw new RuntimeException("Internal error. Please contact support")
      }
    } else {
      createToken(person.email, isSignUp = false).map { token =>
        setupLoginByEmailEnvironment(person, token)
        sendPasswordEmail(person, token.uuid)
      }
    }
    env.authenticatorService.fromRequest.map(auth ⇒ auth.foreach {
      _.updateUser(ActiveUser(id, providerId, inserted, person, Some(member)))
    })
  }

  /**
   * Returns an unique cache id for a person object of current user
   * @param userId User identifier from a social network
   */
  protected def personCacheId(userId: String): String = {
    "user_" + userId
  }

  /**
   * Redirects Viewer to an index page. Otherwise - run action
   */
  protected def redirectViewer(f: Result)(implicit request: Request[Any],
    handler: AuthorisationHandler,
    user: ActiveUser): Result = if (user.account.viewer)
    Redirect(routes.Dashboard.index())
  else
    f

  /**
   * Checks if person data are in cache and redirects to a person data form if not
   */
  protected def checkPersonData(f: UserData ⇒ Result)(implicit request: Request[Any],
    handler: AuthorisationHandler,
    user: ActiveUser): Result = {
    Cache.getAs[UserData](personCacheId(user.id)) map { userData ⇒
      f(userData)
    } getOrElse {
      Redirect(routes.Registration.step2()).
        flashing("error" -> Messages("login.noUserData"))
    }
  }

  /**
    * Sends a create new password email
    * @param person Person
    * @param token Unique token for password creation
    */
  protected def sendPasswordEmail(person: Person, token: String)(implicit request: RequestHeader) = {
    env.mailer.sendEmail(s"Your Happy Melly Account",
      person.email,
      (None, Some(mail.templates.password.html.member(person.firstName, token)))
    )
  }

  /**
   * Returns a person created from registration data
   * @param userData User data
   * @param user ActiveUser
   */
  private def unregisteredPerson(userData: UserData, user: ActiveUser): Person = {
    val photo = new Photo(None, None)
    val fullName = userData.firstName + " " + userData.lastName
    val dateStamp = new DateStamp(DateTime.now(), fullName, DateTime.now(), fullName)
    val person = new Person(None, userData.firstName, userData.lastName, userData.email, None, photo,
      false, 0, None, None, webSite = None, blog = None, active = false,
      dateStamp = dateStamp)
    val address = new Address(countryCode = userData.country)
    person.address_=(address)
    person.socialProfile_=(user.person.socialProfile)
    person
  }

  /**
   * Returns an org created from registration data
   * @param userData User data
   * @return
   */
  private def unregisteredOrg(userData: UserData): Organisation = {
    val org = Organisation(userData.orgData.name, userData.orgData.country)
    val fullName = userData.firstName + " " + userData.lastName
    val dateStamp = new DateStamp(DateTime.now(), fullName, DateTime.now(), fullName)
    org.copy(dateStamp = dateStamp)
  }

  /**
   * Deletes person and org objects if something goes wrong during registration
   * process
   * @param person Person
   * @param org Organisation
   */
  private def clean(person: Person, org: Option[Organisation]) = {
    personService.delete(person.id.get)
    org foreach { x ⇒ orgService.delete(x.id.get) }
  }
}
