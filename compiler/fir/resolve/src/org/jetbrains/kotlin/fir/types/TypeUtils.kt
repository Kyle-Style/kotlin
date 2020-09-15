/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.types

import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.descriptors.Visibility
import org.jetbrains.kotlin.fir.FirFakeSourceElementKind
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.classId
import org.jetbrains.kotlin.fir.diagnostics.ConeSimpleDiagnostic
import org.jetbrains.kotlin.fir.fakeElement
import org.jetbrains.kotlin.fir.render
import org.jetbrains.kotlin.fir.resolve.fullyExpandedType
import org.jetbrains.kotlin.fir.symbols.impl.ConeClassLookupTagWithFixedSymbol
import org.jetbrains.kotlin.fir.types.builder.buildErrorTypeRef
import org.jetbrains.kotlin.fir.types.builder.buildResolvedTypeRef
import org.jetbrains.kotlin.fir.types.impl.ConeClassLikeTypeImpl
import org.jetbrains.kotlin.fir.types.impl.ConeTypeParameterTypeImpl
import org.jetbrains.kotlin.resolve.calls.NewCommonSuperTypeCalculator
import org.jetbrains.kotlin.types.AbstractStrictEqualityTypeChecker
import org.jetbrains.kotlin.types.AbstractTypeApproximator
import org.jetbrains.kotlin.types.TypeApproximatorConfiguration

fun ConeInferenceContext.commonSuperTypeOrNull(types: List<ConeKotlinType>): ConeKotlinType? {
    return when (types.size) {
        0 -> null
        1 -> types.first()
        else -> with(NewCommonSuperTypeCalculator) {
            commonSuperType(types) as ConeKotlinType
        }
    }
}

fun ConeInferenceContext.intersectTypesOrNull(types: List<ConeKotlinType>): ConeKotlinType? {
    return when (types.size) {
        0 -> null
        1 -> types.first()
        else -> ConeTypeIntersector.intersectTypes(this, types)
    }
}

fun ConeDefinitelyNotNullType.Companion.create(original: ConeKotlinType): ConeDefinitelyNotNullType? {
    return when {
        original is ConeDefinitelyNotNullType -> original
        makesSenseToBeDefinitelyNotNull(original) ->
            ConeDefinitelyNotNullType(original.lowerBoundIfFlexible())
        else -> null
    }
}

fun ConeKotlinType.makeConeTypeDefinitelyNotNullOrNotNull(): ConeKotlinType {
    if (this is ConeIntersectionType) {
        return ConeIntersectionType(intersectedTypes.map { it.makeConeTypeDefinitelyNotNullOrNotNull() })
    }
    return ConeDefinitelyNotNullType.create(this) ?: this.withNullability(ConeNullability.NOT_NULL)
}

fun <T : ConeKotlinType> T.withArguments(arguments: Array<out ConeTypeProjection>): T {
    if (this.typeArguments === arguments) {
        return this
    }

    @Suppress("UNCHECKED_CAST")
    return when (this) {
        is ConeClassErrorType -> this
        is ConeClassLikeTypeImpl -> ConeClassLikeTypeImpl(lookupTag, arguments, nullability.isNullable) as T
        is ConeDefinitelyNotNullType -> ConeDefinitelyNotNullType.create(original.withArguments(arguments))!! as T
        else -> error("Not supported: $this: ${this.render()}")
    }
}

fun <T : ConeKotlinType> T.withAttributes(attributes: ConeAttributes): T {
    if (this.attributes == attributes) {
        return this
    }

    @Suppress("UNCHECKED_CAST")
    return when (this) {
        is ConeClassErrorType -> this
        is ConeClassLikeTypeImpl -> ConeClassLikeTypeImpl(lookupTag, typeArguments, nullability.isNullable, attributes)
        is ConeDefinitelyNotNullType -> ConeDefinitelyNotNullType.create(original.withAttributes(attributes))!!
        is ConeTypeParameterTypeImpl -> ConeTypeParameterTypeImpl(lookupTag, nullability.isNullable, attributes)
        else -> error("Not supported: $this: ${this.render()}")
    } as T
}

fun ConeTypeContext.hasNullableSuperType(type: ConeKotlinType): Boolean {
    if (type is ConeClassLikeType) return false

    if (type !is ConeLookupTagBasedType) return false // TODO?
    val symbol = type.lookupTag
    for (superType in symbol.supertypes()) {
        if (superType.isNullableType()) return true
    }
//
//    for (KotlinType supertype : getImmediateSupertypes(type)) {
//        if (isNullableType(supertype)) return true;
//    }

    return false
}

fun <T : ConeKotlinType> T.withNullability(nullability: ConeNullability, typeContext: ConeInferenceContext? = null): T {
    if (this.nullability == nullability) {
        return this
    }

    @Suppress("UNCHECKED_CAST")
    return when (this) {
        is ConeClassErrorType -> this
        is ConeClassLikeTypeImpl -> ConeClassLikeTypeImpl(lookupTag, typeArguments, nullability.isNullable, attributes)
        is ConeTypeParameterTypeImpl -> ConeTypeParameterTypeImpl(lookupTag, nullability.isNullable, attributes)
        is ConeFlexibleType -> {
            if (nullability == ConeNullability.UNKNOWN) {
                if (lowerBound.nullability != upperBound.nullability || lowerBound.nullability == ConeNullability.UNKNOWN) {
                    return this
                }
            }
            coneFlexibleOrSimpleType(typeContext, lowerBound.withNullability(nullability), upperBound.withNullability(nullability))
        }
        is ConeTypeVariableType -> ConeTypeVariableType(nullability, lookupTag)
        is ConeCapturedType -> ConeCapturedType(captureStatus, lowerType, nullability, constructor, attributes)
        is ConeIntersectionType -> when (nullability) {
            ConeNullability.NULLABLE -> this.mapTypes {
                it.withNullability(nullability)
            }
            ConeNullability.UNKNOWN -> this // TODO: is that correct?
            ConeNullability.NOT_NULL -> this
        }
        is ConeStubType -> ConeStubType(variable, nullability)
        is ConeDefinitelyNotNullType -> when (nullability) {
            ConeNullability.NOT_NULL -> this
            ConeNullability.NULLABLE -> original.withNullability(nullability)
            ConeNullability.UNKNOWN -> original.withNullability(nullability)
        }
        is ConeIntegerLiteralType -> ConeIntegerLiteralTypeImpl(value, isUnsigned, nullability)
        else -> error("sealed: ${this::class}")
    } as T
}

fun coneFlexibleOrSimpleType(
    typeContext: ConeInferenceContext?,
    lowerBound: ConeKotlinType,
    upperBound: ConeKotlinType,
): ConeKotlinType {
    if (lowerBound is ConeFlexibleType) {
        return coneFlexibleOrSimpleType(typeContext, lowerBound.lowerBound, upperBound)
    }
    if (upperBound is ConeFlexibleType) {
        return coneFlexibleOrSimpleType(typeContext, lowerBound, upperBound.upperBound)
    }
    return when {
        typeContext != null && AbstractStrictEqualityTypeChecker.strictEqualTypes(typeContext, lowerBound, upperBound) -> {
            lowerBound
        }
        typeContext == null && lowerBound == upperBound -> {
            lowerBound
        }
        else -> {
            ConeFlexibleType(lowerBound, upperBound)
        }
    }
}

fun ConeKotlinType.isExtensionFunctionType(session: FirSession): Boolean {
    val type = this.lowerBoundIfFlexible().fullyExpandedType(session)
    return type.attributes.extensionFunctionType != null
}

fun FirTypeRef.isExtensionFunctionType(session: FirSession): Boolean {
    return coneTypeSafe<ConeKotlinType>()?.isExtensionFunctionType(session) == true
}

fun ConeKotlinType.isUnsafeVarianceType(session: FirSession): Boolean {
    val type = this.lowerBoundIfFlexible().fullyExpandedType(session)
    return type.attributes.unsafeVarianceType != null
}

fun FirTypeRef.isUnsafeVarianceType(session: FirSession): Boolean {
    return coneTypeSafe<ConeKotlinType>()?.isUnsafeVarianceType(session) == true
}

// Unlike other cases, return types may be implicit, i.e. unresolved
// But in that cases newType should also be `null`
fun FirTypeRef.withReplacedReturnType(newType: ConeKotlinType?): FirTypeRef {
    require(this is FirResolvedTypeRef || newType == null)
    if (newType == null) return this

    return buildResolvedTypeRef {
        source = this@withReplacedReturnType.source
        type = newType
        annotations += this@withReplacedReturnType.annotations
    }
}

fun FirTypeRef.withReplacedConeType(
    newType: ConeKotlinType?,
    firFakeSourceElementKind: FirFakeSourceElementKind? = null
): FirResolvedTypeRef {
    require(this is FirResolvedTypeRef)
    if (newType == null) return this

    return buildResolvedTypeRef {
        source = if (firFakeSourceElementKind != null)
            this@withReplacedConeType.source?.fakeElement(firFakeSourceElementKind)
        else
            this@withReplacedConeType.source
        type = newType
        annotations += this@withReplacedConeType.annotations
    }
}

private fun ConeKotlinType.requiresApproximationInPublicPosition(): Boolean {
    return when (this) {
        is ConeIntegerLiteralType,
        is ConeCapturedType,
        is ConeDefinitelyNotNullType,
        is ConeIntersectionType -> true
        is ConeClassLikeType -> typeArguments.any {
            it is ConeKotlinTypeProjection && it.type.requiresApproximationInPublicPosition()
        }
        else -> false
    }
}

fun FirTypeRef.approximateTypeIfNeeded(
    approximator: AbstractTypeApproximator,
    containingCallableVisibility: Visibility? = Visibilities.Public,
    isInlineFunction: Boolean = false,
    hideLocalType: Boolean = true
): FirTypeRef {
    val approximatedType = if (this is FirResolvedTypeRef &&
        (containingCallableVisibility == Visibilities.Public || containingCallableVisibility == Visibilities.Protected)
    ) {
        if (type.requiresApproximationInPublicPosition()) {
            this.withReplacedConeType(
                approximator.approximateToSuperType(
                    this.type, TypeApproximatorConfiguration.PublicDeclaration
                ) as? ConeKotlinType
            )
        } else {
            this
        }
    } else {
        this
    }
    return if (hideLocalType) {
        approximatedType.hideLocalTypeIfNeeded(containingCallableVisibility, isInlineFunction)
    } else {
        approximatedType
    }
}

/*
 * Suppose a function without an explicit return type just returns an anonymous object:
 *
 *   fun foo(...) = object : ObjectSuperType {
 *     override fun ...
 *   }
 *
 * Without unwrapping, the return type ended up with that anonymous object (<no name provided>), while the resolved super type, which
 * acts like an implementing interface, is a better fit. In fact, exposing an anonymous object types is prohibited for certain cases,
 * e.g., KT-33917. We can also apply this to any local types.
 */
private fun FirTypeRef.hideLocalTypeIfNeeded(
    containingCallableVisibility: Visibility?,
    isInlineFunction: Boolean = false
): FirTypeRef {
    if (containingCallableVisibility == null) {
        return this
    }
    // Approximate types for non-private (all but package private or private) members.
    // Also private inline functions, as per KT-33917.
    if (containingCallableVisibility == Visibilities.Public ||
        containingCallableVisibility == Visibilities.Protected ||
        containingCallableVisibility == Visibilities.Internal ||
        (containingCallableVisibility == Visibilities.Private && isInlineFunction)
    ) {
        val firClass =
            (((this as? FirResolvedTypeRef)
                ?.type as? ConeClassLikeType)
                ?.lookupTag as? ConeClassLookupTagWithFixedSymbol)
                ?.symbol?.fir
        if (firClass?.classId?.isLocal != true) {
            return this
        }
        if (firClass.superTypeRefs.size > 1) {
            return buildErrorTypeRef {
                diagnostic = ConeSimpleDiagnostic("Cannot hide local type ${firClass.render()}")
            }
        }
        val superType = firClass.superTypeRefs.single()
        if (superType is FirResolvedTypeRef && !superType.isAny) {
            return superType
        }
    }
    return this
}

