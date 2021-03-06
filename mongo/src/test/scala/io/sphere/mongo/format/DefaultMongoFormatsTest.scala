package io.sphere.mongo.format

import io.sphere.mongo.format.DefaultMongoFormats._
import org.bson.types.BasicBSONList
import org.scalacheck.Gen
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import org.scalatest.{MustMatchers, WordSpec}

import scala.collection.JavaConverters._

class DefaultMongoFormatsTest extends WordSpec with MustMatchers with GeneratorDrivenPropertyChecks {

  "DefaultMongoFormats" must {
    "support List" in {
      val format = listFormat[String]
      val list = Gen.listOf(Gen.alphaNumStr)

      forAll(list) { l ⇒
        val dbo = format.toMongoValue(l)
        dbo.asInstanceOf[BasicBSONList].asScala.toList must be (l)
        val resultList = format.fromMongoValue(dbo)
        resultList must be (l)
      }
    }

    "support Set" in {
      val format = setFormat[String]
      val set = Gen.listOf(Gen.alphaNumStr).map(_.toSet)

      forAll(set) { s ⇒
        val dbo = format.toMongoValue(s)
        dbo.asInstanceOf[BasicBSONList].asScala.toSet must be (s)
        val resultSet = format.fromMongoValue(dbo)
        resultSet must be (s)
      }
    }
  }

}
