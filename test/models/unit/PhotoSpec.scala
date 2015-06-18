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
package models.unit

import models.{ Photo, SocialProfile }
import org.specs2.mutable._

class PhotoSpec extends Specification {

  "Url to Facebook photo" should {
    "be returned when url to profile is valid" in {
      Photo.facebookUrl("http://www.facebook.com/skotlov") must_== "http://graph.facebook.com/skotlov/picture?type=large"
      Photo.facebookUrl("https://www.facebook.com/aolchik") must_== "http://graph.facebook.com/aolchik/picture?type=large"
    }
    "not be returned when url to profile is invalid" in {
      Photo.facebookUrl("http://www.facebook.com/") must_== ""
    }
  }

  "Photo object with a valid type should be returned" >> {
    "if the type is Gravatar" in {
      val profile = SocialProfile(email = "test@test.com")
      Photo("gravatar", profile).id must_== Some("gravatar")
    }
    "if the type is Facebook" in {
      val profile = SocialProfile(email = "test@test.com",
        facebookUrl = Some("http://www.facebook.com/skotlov"))
      Photo("facebook", profile).id must_== Some("facebook")
    }
    "if the type is anything but Gravatar and Facebook" in {
      val profile = SocialProfile(email = "test@test.com")
      Photo("test", profile).id must_== None
    }
  }
}