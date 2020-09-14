/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.backend

import org.jetbrains.kotlin.fir.backend.generators.AnnotationGenerator
import org.jetbrains.kotlin.fir.expressions.FirAnnotationCall
import org.jetbrains.kotlin.fir.resolve.fullyExpandedType
import org.jetbrains.kotlin.fir.resolve.inference.inferenceComponents
import org.jetbrains.kotlin.fir.resolve.toSymbol
import org.jetbrains.kotlin.fir.symbols.StandardClassIds
import org.jetbrains.kotlin.fir.types.*
import org.jetbrains.kotlin.fir.types.impl.*
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeArgument
import org.jetbrains.kotlin.ir.types.impl.IrSimpleTypeImpl
import org.jetbrains.kotlin.ir.types.impl.IrStarProjectionImpl
import org.jetbrains.kotlin.ir.types.impl.makeTypeProjection
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.types.TypeApproximatorConfiguration
import org.jetbrains.kotlin.types.Variance

class Fir2IrTypeConverter(
    private val components: Fir2IrComponents
) : Fir2IrComponents by components {
    private val annotationGenerator = AnnotationGenerator(this)

    internal val classIdToSymbolMap = mapOf(
        StandardClassIds.Nothing to irBuiltIns.nothingClass,
        StandardClassIds.Unit to irBuiltIns.unitClass,
        StandardClassIds.Boolean to irBuiltIns.booleanClass,
        StandardClassIds.String to irBuiltIns.stringClass,
        StandardClassIds.Any to irBuiltIns.anyClass,
        StandardClassIds.Long to irBuiltIns.longClass,
        StandardClassIds.Int to irBuiltIns.intClass,
        StandardClassIds.Short to irBuiltIns.shortClass,
        StandardClassIds.Byte to irBuiltIns.byteClass,
        StandardClassIds.Float to irBuiltIns.floatClass,
        StandardClassIds.Double to irBuiltIns.doubleClass,
        StandardClassIds.Char to irBuiltIns.charClass,
        StandardClassIds.Array to irBuiltIns.arrayClass
    )

    internal val classIdToTypeMap = mapOf(
        StandardClassIds.Nothing to irBuiltIns.nothingType,
        StandardClassIds.Unit to irBuiltIns.unitType,
        StandardClassIds.Boolean to irBuiltIns.booleanType,
        StandardClassIds.String to irBuiltIns.stringType,
        StandardClassIds.Any to irBuiltIns.anyType,
        StandardClassIds.Long to irBuiltIns.longType,
        StandardClassIds.Int to irBuiltIns.intType,
        StandardClassIds.Short to irBuiltIns.shortType,
        StandardClassIds.Byte to irBuiltIns.byteType,
        StandardClassIds.Float to irBuiltIns.floatType,
        StandardClassIds.Double to irBuiltIns.doubleType,
        StandardClassIds.Char to irBuiltIns.charType
    )

    fun FirTypeRef.toIrType(typeContext: ConversionTypeContext = ConversionTypeContext.DEFAULT): IrType {
        return when (this) {
            !is FirResolvedTypeRef -> createErrorType()
            !is FirImplicitBuiltinTypeRef -> type.toIrType(typeContext, annotations = annotations)
            is FirImplicitNothingTypeRef -> irBuiltIns.nothingType
            is FirImplicitUnitTypeRef -> irBuiltIns.unitType
            is FirImplicitBooleanTypeRef -> irBuiltIns.booleanType
            is FirImplicitStringTypeRef -> irBuiltIns.stringType
            is FirImplicitAnyTypeRef -> irBuiltIns.anyType
            is FirImplicitIntTypeRef -> irBuiltIns.intType
            is FirImplicitNullableAnyTypeRef -> irBuiltIns.anyNType
            is FirImplicitNullableNothingTypeRef -> irBuiltIns.nothingNType
            else -> type.toIrType(typeContext, annotations = annotations)
        }
    }

    fun ConeKotlinType.toIrType(
        typeContext: ConversionTypeContext = ConversionTypeContext.DEFAULT,
        visitedCapturedTypes: MutableSet<ConeCapturedType> = mutableSetOf(),
        annotations: List<FirAnnotationCall> = emptyList()
    ): IrType {
        return when (this) {
            is ConeKotlinErrorType -> createErrorType()
            is ConeLookupTagBasedType -> {
                val classId = this.classId
                val irSymbol = getBuiltInClassSymbol(classId) ?: run {
                    val firSymbol = this.lookupTag.toSymbol(session) ?: return createErrorType()
                    firSymbol.toSymbol(session, classifierStorage, typeContext)
                }
                val typeAnnotations: MutableList<IrConstructorCall> =
                    if (attributes.extensionFunctionType == null) mutableListOf()
                    else mutableListOf(builtIns.extensionFunctionTypeAnnotationConstructorCall())
                typeAnnotations += with(annotationGenerator) { annotations.toIrAnnotations() }
                IrSimpleTypeImpl(
                    irSymbol, !typeContext.definitelyNotNull && this.isMarkedNullable,
                    fullyExpandedType(session).typeArguments.map { it.toIrTypeArgument(typeContext, visitedCapturedTypes) },
                    typeAnnotations
                )
            }
            is ConeFlexibleType -> {
                // TODO: yet we take more general type. Not quite sure it's Ok
                upperBound.toIrType(typeContext, visitedCapturedTypes)
            }
            is ConeCapturedType -> {
                visitedCapturedTypes += this
                lowerType?.toIrType(typeContext, visitedCapturedTypes)
                    ?: constructor.supertypes!!.first().toIrType(typeContext, visitedCapturedTypes)
            }
            is ConeDefinitelyNotNullType -> {
                original.toIrType(typeContext.definitelyNotNull(), visitedCapturedTypes)
            }
            is ConeIntersectionType -> {
                // TODO: add intersectionTypeApproximation
                intersectedTypes.first().toIrType(typeContext, visitedCapturedTypes)
            }
            is ConeStubType -> createErrorType()
            is ConeIntegerLiteralType -> createErrorType()
        }
    }

    private fun ConeTypeProjection.toIrTypeArgument(
        typeContext: ConversionTypeContext,
        visitedCapturedTypes: MutableSet<ConeCapturedType>
    ): IrTypeArgument {
        val convertedType = if (this is ConeKotlinTypeProjection && type in visitedCapturedTypes) {
            (session.inferenceComponents.approximator.approximateToSuperType(
                type, TypeApproximatorConfiguration.SubtypeCapturedTypesApproximation
            ) as? ConeKotlinType)?.toIrType(typeContext, visitedCapturedTypes)
        } else null
        return when (this) {
            ConeStarProjection -> IrStarProjectionImpl
            is ConeKotlinTypeProjectionIn -> {
                val irType = convertedType ?: type.toIrType(typeContext, visitedCapturedTypes)
                makeTypeProjection(irType, Variance.IN_VARIANCE)
            }
            is ConeKotlinTypeProjectionOut -> {
                val irType = convertedType ?: type.toIrType(typeContext, visitedCapturedTypes)
                makeTypeProjection(irType, Variance.OUT_VARIANCE)
            }
            is ConeKotlinType -> {
                val irType = convertedType ?: toIrType(typeContext, visitedCapturedTypes)
                makeTypeProjection(irType, Variance.INVARIANT)
            }
        }
    }

    private fun getArrayClassSymbol(classId: ClassId?): IrClassSymbol? {
        val primitiveId = StandardClassIds.elementTypeByPrimitiveArrayType[classId] ?: return null
        val irType = classIdToTypeMap[primitiveId]
        return irBuiltIns.primitiveArrayForType[irType] ?: error("Strange primitiveId $primitiveId from array: $classId")
    }

    private fun getBuiltInClassSymbol(classId: ClassId?): IrClassSymbol? {
        return classIdToSymbolMap[classId] ?: getArrayClassSymbol(classId)
    }
}

fun FirTypeRef.toIrType(
    typeConverter: Fir2IrTypeConverter,
    typeContext: ConversionTypeContext = ConversionTypeContext.DEFAULT
): IrType =
    with(typeConverter) {
        toIrType(typeContext)
    }
