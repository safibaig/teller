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
 * If you have questions concerning this license or the applicable additional terms, you may contact
 * by email Sergey Kotlov, sergey.kotlov@happymelly.com or
 * in writing Happy Melly One, Handelsplein 37, Rotterdam, The Netherlands, 3071 PR
 */
package controllers

import javax.inject.Inject

import be.objectify.deadbolt.scala.cache.HandlerCache
import be.objectify.deadbolt.scala.{ActionBuilders, DeadboltActions}
import models.UserRole.Role._
import models.repository.Repositories
import play.api.Play.current
import play.api.i18n.MessagesApi
import play.api.libs.json.Json
import play.api.libs.ws.WS
import services.TellerRuntimeEnvironment

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.Try

class Urls @Inject() (override implicit val env: TellerRuntimeEnvironment,
                      override val messagesApi: MessagesApi,
                      val services: Repositories,
                      deadbolt: DeadboltActions, handlers: HandlerCache, actionBuilder: ActionBuilders)
  extends Security(deadbolt, handlers, actionBuilder, services)(messagesApi, env) {

  /**
   * Validates the given url points to an existing page
   *
   * @param url Url to check
   */
  def validate(url: String) = RestrictedAction(Viewer) { implicit request ⇒
    implicit handler ⇒ implicit user ⇒
      WS.url(url).head().flatMap { response =>
        if (response.status >= 200 && response.status < 300)
          jsonOk(Json.obj("result" -> "valid"))
        else
          jsonOk(Json.obj("result" -> "invalid"))
      }.recover { case _ =>
        Ok(Json.prettyPrint(Json.obj("result" -> "invalid")))
      }
  }
}