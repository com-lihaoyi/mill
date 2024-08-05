package mill.scalalib

import upickle.default.{ReadWriter => RW}


trait JsonFormatters {
  import JsonFormatters.mirrors.given

  implicit lazy val publicationFormat: RW[coursier.core.Publication] = upickle.default.macroRW
  implicit lazy val extensionFormat: RW[coursier.core.Extension] = upickle.default.macroRW

  implicit lazy val modFormat: RW[coursier.Module] = upickle.default.macroRW
  implicit lazy val depFormat: RW[coursier.core.Dependency] = upickle.default.macroRW
  implicit lazy val minimizedExclusionsFormat: RW[coursier.core.MinimizedExclusions] =
    upickle.default.macroRW
  implicit lazy val exclusionDataFormat: RW[coursier.core.MinimizedExclusions.ExclusionData] =
    RW.merge(
      upickle.default.macroRW[coursier.core.MinimizedExclusions.ExcludeNone.type],
      upickle.default.macroRW[coursier.core.MinimizedExclusions.ExcludeAll.type],
      upickle.default.macroRW[coursier.core.MinimizedExclusions.ExcludeSpecific]
    )
  implicit lazy val attrFormat: RW[coursier.Attributes] = upickle.default.macroRW
  implicit lazy val orgFormat: RW[coursier.Organization] = upickle.default.macroRW
  implicit lazy val modNameFormat: RW[coursier.ModuleName] = upickle.default.macroRW
  implicit lazy val configurationFormat: RW[coursier.core.Configuration] = upickle.default.macroRW
  implicit lazy val typeFormat: RW[coursier.core.Type] = upickle.default.macroRW
  implicit lazy val classifierFormat: RW[coursier.core.Classifier] = upickle.default.macroRW

}
object JsonFormatters extends JsonFormatters {
  private[JsonFormatters] object mirrors {
    import scala.deriving.Mirror

    // TODO: should we make a macro to generate mirrors for non-case classes?
    // TODO: GENERATED CODE by ci/scripts/manual_mirror_gen.sc - DO NOT EDIT
    given Mirror_coursier_core_Publication: Mirror.Product with {
      final type MirroredMonoType = coursier.core.Publication
      final type MirroredType = coursier.core.Publication
      final type MirroredElemTypes = (String, coursier.core.Type, coursier.core.Extension, coursier.core.Classifier)
      final type MirroredElemLabels = ("name", "type", "ext", "classifier")

      final def fromProduct(p: scala.Product): coursier.core.Publication = {
        val _1: String = p.productElement(0).asInstanceOf[String]
        val _2: coursier.core.Type = p.productElement(1).asInstanceOf[coursier.core.Type]
        val _3: coursier.core.Extension = p.productElement(2).asInstanceOf[coursier.core.Extension]
        val _4: coursier.core.Classifier = p.productElement(3).asInstanceOf[coursier.core.Classifier]

        coursier.core.Publication.apply(_1,_2,_3,_4)
      }
    }

    given Mirror_coursier_core_Extension: Mirror.Product with {
      final type MirroredMonoType = coursier.core.Extension
      final type MirroredType = coursier.core.Extension
      final type MirroredElemTypes = String *: EmptyTuple
      final type MirroredElemLabels = "value" *: EmptyTuple

      final def fromProduct(p: scala.Product): coursier.core.Extension = {
        val _1: String = p.productElement(0).asInstanceOf[String]

        coursier.core.Extension.apply(_1)
      }
    }

    given Mirror_coursier_core_Module: Mirror.Product with {
      final type MirroredMonoType = coursier.core.Module
      final type MirroredType = coursier.core.Module
      final type MirroredElemTypes = (coursier.core.Organization, coursier.core.ModuleName, Map[String, String])
      final type MirroredElemLabels = ("organization", "name", "attributes")

      final def fromProduct(p: scala.Product): coursier.core.Module = {
        val _1: coursier.core.Organization = p.productElement(0).asInstanceOf[coursier.core.Organization]
        val _2: coursier.core.ModuleName = p.productElement(1).asInstanceOf[coursier.core.ModuleName]
        val _3: Map[String, String] = p.productElement(2).asInstanceOf[Map[String, String]]

        coursier.core.Module.apply(_1,_2,_3)
      }
    }

    given Mirror_coursier_core_Dependency: Mirror.Product with {
      final type MirroredMonoType = coursier.core.Dependency
      final type MirroredType = coursier.core.Dependency
      final type MirroredElemTypes = (coursier.core.Module, String, coursier.core.Configuration, coursier.core.MinimizedExclusions, coursier.core.Publication, Boolean, Boolean)
      final type MirroredElemLabels = ("module", "version", "configuration", "minimizedExclusions", "publication", "optional", "transitive")

      final def fromProduct(p: scala.Product): coursier.core.Dependency = {
        val _1: coursier.core.Module = p.productElement(0).asInstanceOf[coursier.core.Module]
        val _2: String = p.productElement(1).asInstanceOf[String]
        val _3: coursier.core.Configuration = p.productElement(2).asInstanceOf[coursier.core.Configuration]
        val _4: coursier.core.MinimizedExclusions = p.productElement(3).asInstanceOf[coursier.core.MinimizedExclusions]
        val _5: coursier.core.Publication = p.productElement(4).asInstanceOf[coursier.core.Publication]
        val _6: Boolean = p.productElement(5).asInstanceOf[Boolean]
        val _7: Boolean = p.productElement(6).asInstanceOf[Boolean]

        coursier.core.Dependency.apply(_1,_2,_3,_4,_5,_6,_7)
      }
    }

    given Mirror_coursier_core_MinimizedExclusions: Mirror.Product with {
      final type MirroredMonoType = coursier.core.MinimizedExclusions
      final type MirroredType = coursier.core.MinimizedExclusions
      final type MirroredElemTypes = coursier.core.MinimizedExclusions.ExclusionData *: EmptyTuple
      final type MirroredElemLabels = "data" *: EmptyTuple

      final def fromProduct(p: scala.Product): coursier.core.MinimizedExclusions = {
        val _1: coursier.core.MinimizedExclusions.ExclusionData = p.productElement(0).asInstanceOf[coursier.core.MinimizedExclusions.ExclusionData]

        coursier.core.MinimizedExclusions.apply(_1)
      }
    }

    given Mirror_coursier_core_MinimizedExclusions_ExcludeSpecific: Mirror.Product with {
      final type MirroredMonoType = coursier.core.MinimizedExclusions.ExcludeSpecific
      final type MirroredType = coursier.core.MinimizedExclusions.ExcludeSpecific
      final type MirroredElemTypes = (Set[coursier.core.Organization], Set[coursier.core.ModuleName], Set[(coursier.core.Organization, coursier.core.ModuleName)])
      final type MirroredElemLabels = ("byOrg", "byModule", "specific")

      final def fromProduct(p: scala.Product): coursier.core.MinimizedExclusions.ExcludeSpecific = {
        val _1: Set[coursier.core.Organization] = p.productElement(0).asInstanceOf[Set[coursier.core.Organization]]
        val _2: Set[coursier.core.ModuleName] = p.productElement(1).asInstanceOf[Set[coursier.core.ModuleName]]
        val _3: Set[(coursier.core.Organization, coursier.core.ModuleName)] = p.productElement(2).asInstanceOf[Set[(coursier.core.Organization, coursier.core.ModuleName)]]

        coursier.core.MinimizedExclusions.ExcludeSpecific.apply(_1,_2,_3)
      }
    }

    given Mirror_coursier_core_Attributes: Mirror.Product with {
      final type MirroredMonoType = coursier.core.Attributes
      final type MirroredType = coursier.core.Attributes
      final type MirroredElemTypes = (coursier.core.Type, coursier.core.Classifier)
      final type MirroredElemLabels = ("type", "classifier")

      final def fromProduct(p: scala.Product): coursier.core.Attributes = {
        val _1: coursier.core.Type = p.productElement(0).asInstanceOf[coursier.core.Type]
        val _2: coursier.core.Classifier = p.productElement(1).asInstanceOf[coursier.core.Classifier]

        coursier.core.Attributes.apply(_1,_2)
      }
    }

    given Mirror_coursier_core_Organization: Mirror.Product with {
      final type MirroredMonoType = coursier.core.Organization
      final type MirroredType = coursier.core.Organization
      final type MirroredElemTypes = String *: EmptyTuple
      final type MirroredElemLabels = "value" *: EmptyTuple

      final def fromProduct(p: scala.Product): coursier.core.Organization = {
        val _1: String = p.productElement(0).asInstanceOf[String]

        coursier.core.Organization.apply(_1)
      }
    }

    given Mirror_coursier_core_ModuleName: Mirror.Product with {
      final type MirroredMonoType = coursier.core.ModuleName
      final type MirroredType = coursier.core.ModuleName
      final type MirroredElemTypes = String *: EmptyTuple
      final type MirroredElemLabels = "value" *: EmptyTuple

      final def fromProduct(p: scala.Product): coursier.core.ModuleName = {
        val _1: String = p.productElement(0).asInstanceOf[String]

        coursier.core.ModuleName.apply(_1)
      }
    }

    given Mirror_coursier_core_Configuration: Mirror.Product with {
      final type MirroredMonoType = coursier.core.Configuration
      final type MirroredType = coursier.core.Configuration
      final type MirroredElemTypes = String *: EmptyTuple
      final type MirroredElemLabels = "value" *: EmptyTuple

      final def fromProduct(p: scala.Product): coursier.core.Configuration = {
        val _1: String = p.productElement(0).asInstanceOf[String]

        coursier.core.Configuration.apply(_1)
      }
    }

    given Mirror_coursier_core_Type: Mirror.Product with {
      final type MirroredMonoType = coursier.core.Type
      final type MirroredType = coursier.core.Type
      final type MirroredElemTypes = String *: EmptyTuple
      final type MirroredElemLabels = "value" *: EmptyTuple

      final def fromProduct(p: scala.Product): coursier.core.Type = {
        val _1: String = p.productElement(0).asInstanceOf[String]

        coursier.core.Type.apply(_1)
      }
    }

    given Mirror_coursier_core_Classifier: Mirror.Product with {
      final type MirroredMonoType = coursier.core.Classifier
      final type MirroredType = coursier.core.Classifier
      final type MirroredElemTypes = String *: EmptyTuple
      final type MirroredElemLabels = "value" *: EmptyTuple

      final def fromProduct(p: scala.Product): coursier.core.Classifier = {
        val _1: String = p.productElement(0).asInstanceOf[String]

        coursier.core.Classifier.apply(_1)
      }
    }
  }
}
