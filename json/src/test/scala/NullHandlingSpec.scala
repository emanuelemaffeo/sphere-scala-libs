package io.sphere.json

import io.sphere.json.generic._
import org.scalatest.{MustMatchers, WordSpec}

class NullHandlingSpec extends WordSpec with MustMatchers {
  "JSON deserialization" must {
    "should accept undefined fields and use default values for them" in {
      val user = getFromJSON[Jeans]("{}")

      user must be (Jeans(None, None, Set.empty, "secret"))
    }

    "should accept null values and use default values for them" in {
      val user = getFromJSON[Jeans](
        """
          {
            "leftPocket": null,
            "rightPocket": null,
            "backPocket": null,
            "hiddenPocket": null
          }
        """)

      user must be (Jeans(None, None, Set.empty, "secret"))
    }

    "should accept not-null values and use them" in {
      val user = getFromJSON[Jeans](
        """
          {
            "leftPocket": "Axe",
            "rightPocket": "Magic powder",
            "backPocket": ["Magic wand", "Rusty sword"],
            "hiddenPocket": "The potion of healing"
          }
        """)

      user must be (Jeans(
        Some("Axe"),
        Some("Magic powder"),
        Set("Magic wand", "Rusty sword"),
        "The potion of healing"))
    }
  }
}

case class Jeans(
  leftPocket: Option[String] = None,
  rightPocket: Option[String],
  backPocket: Set[String] = Set.empty,
  hiddenPocket: String = "secret")

object Jeans {
  implicit val json: JSON[Jeans] = deriveJSON[Jeans]
}