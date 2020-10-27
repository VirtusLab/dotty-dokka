package dotty.dokka

import org.jetbrains.dokka.transformers.documentation.DocumentableTransformer
import org.jetbrains.dokka.model._
import collection.JavaConverters
import collection.JavaConverters._
import org.jetbrains.dokka.plugability.DokkaContext
import org.jetbrains.dokka.links.DRI
import org.jetbrains.dokka.model.properties._

import dotty.dokka.model._
import dotty.dokka.model.api._

class ImplicitMembersExtensionTransformer(ctx: DokkaContext) extends DocumentableTransformer:
    override def invoke(original: DModule, context: DokkaContext): DModule = 
        val classlikeMap = original.driMap
        
        def expandMember(outerMembers: Seq[Member])(c: Member): Member = 
            val companion = c match 
                case classlike: DClass => ClasslikeExtension.getFrom(classlike).flatMap(_.companion).map(classlikeMap)
                case _ => None

            val allParents = c.parents.flatMap { p => 
                classlikeMap.get(p.dri)
            }

            val parentCompanions = allParents.flatMap { _ match
                    case cls: DClasslike => ClasslikeExtension.getFrom(cls).flatMap(_.companion).map(classlikeMap)
                    case _ => None
            }     

            val implictSources = outerMembers ++ companion.toSeq ++ parentCompanions // We can expand this on generic etc.

            val applicableDRIs = c.parents.map(_.dri).toSet + c.dri

            def collectApplicableMembers(source: Member): Seq[Member] = source.allMembers.flatMap {
                case m @ Member(_, _, _, Kind.Extension(ExtensionTarget(_, _, to)), Origin.DefinedWithin) if applicableDRIs.contains(to) => 
                    Seq(m.withOrigin(Origin.ExtensionFrom(source.name, source.dri)).withKind(Kind.Def))
                case m @ Member(_, _, _, conversionProvider: ImplicitConversionProvider, Origin.DefinedWithin) =>
                    conversionProvider.conversion match 
                        case Some(ImplicitConversion(from, to, fromType, toType)) if applicableDRIs.contains(from) =>
                            classlikeMap.get(to).toSeq.flatMap { owner =>
                                val newMembers = owner.allMembers.filter(_.origin match
                                    case Origin.DefinedWithin => true
                                    case Origin.InheritedFrom(_, _) => true
                                    case _ => false
                                )
                                newMembers.map(_.withOrigin(Origin.ImplicitlyAddedBy(m.name, m.dri, fromType, toType)))
                            } 
                        case _ =>
                            Nil
                case _ =>
                    None    
            }

            val newImplicitMembers = implictSources.flatMap(collectApplicableMembers).distinct
            val expandedMembers = c.allMembers.map(expandMember(newImplicitMembers ++ Seq(c)))
            c.withMembers(newImplicitMembers ++ expandedMembers)

        original.updatePackages(_.map(expandMember(Nil)(_).asInstanceOf[DPackage]))
