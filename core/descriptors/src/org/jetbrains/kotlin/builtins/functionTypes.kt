/*
 * Copyright 2010-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.builtins

import org.jetbrains.kotlin.builtins.functions.BuiltInFictitiousFunctionClassFactory
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.descriptors.SourceElement
import org.jetbrains.kotlin.descriptors.ValueParameterDescriptor
import org.jetbrains.kotlin.descriptors.annotations.AnnotationDescriptorImpl
import org.jetbrains.kotlin.descriptors.annotations.Annotations
import org.jetbrains.kotlin.descriptors.annotations.AnnotationsImpl
import org.jetbrains.kotlin.descriptors.impl.ValueParameterDescriptorImpl
import org.jetbrains.kotlin.name.FqNameUnsafe
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameUnsafe
import org.jetbrains.kotlin.types.*
import org.jetbrains.kotlin.utils.DFS
import java.util.*

val KotlinType.isFunctionTypeOrSubtype: Boolean
    get() = isFunctionType || DFS.dfsFromNode(
            this,
            DFS.Neighbors { it.constructor.supertypes },
            DFS.VisitedWithSet(),
            object : DFS.AbstractNodeHandler<KotlinType, Boolean>() {
                private var result = false

                override fun beforeChildren(current: KotlinType): Boolean {
                    if (current.isFunctionType) {
                        result = true
                    }
                    return !result
                }

                override fun result() = result
            }
    )

val KotlinType.isFunctionType: Boolean
    get() {
        val descriptor = constructor.declarationDescriptor
        return descriptor != null && isNumberedFunctionClassFqName(descriptor.fqNameUnsafe)
    }

val KotlinType.isNonExtensionFunctionType: Boolean
    get() = isFunctionType && !isTypeAnnotatedWithExtensionFunctionType

val KotlinType.isExtensionFunctionType: Boolean
    get() = isFunctionType && isTypeAnnotatedWithExtensionFunctionType

private val KotlinType.isTypeAnnotatedWithExtensionFunctionType: Boolean
    get() = annotations.findAnnotation(KotlinBuiltIns.FQ_NAMES.extensionFunctionType) != null

/**
 * @return true if this is an FQ name of a fictitious class representing the function type,
 * e.g. kotlin.Function1 (but NOT kotlin.reflect.KFunction1)
 */
fun isNumberedFunctionClassFqName(fqName: FqNameUnsafe): Boolean {
    val segments = fqName.pathSegments()
    if (segments.size != 2) return false

    if (KotlinBuiltIns.BUILT_INS_PACKAGE_NAME != segments.first()) return false

    val shortName = segments.last().asString()
    return BuiltInFictitiousFunctionClassFactory.isFunctionClassName(shortName, KotlinBuiltIns.BUILT_INS_PACKAGE_FQ_NAME)
}

fun getReceiverTypeFromFunctionType(type: KotlinType): KotlinType? {
    assert(type.isFunctionType) { "Not a function type: $type" }
    return if (type.isTypeAnnotatedWithExtensionFunctionType) type.arguments.first().type else null
}

fun createValueParametersFromFunctionType(
        functionDescriptor: FunctionDescriptor, parameterTypes: List<TypeProjection>
): List<ValueParameterDescriptor> {
    return parameterTypes.mapIndexed { i, typeProjection ->
        ValueParameterDescriptorImpl(
                functionDescriptor, null, i, Annotations.EMPTY,
                Name.identifier("p${i + 1}"), typeProjection.type,
                /* declaresDefaultValue = */ false,
                /* isCrossinline = */ false,
                /* isNoinline = */ false,
                null, SourceElement.NO_SOURCE
        )
    }
}

fun getReturnTypeFromFunctionType(type: KotlinType): KotlinType {
    assert(type.isFunctionType) { "Not a function type: $type" }
    return type.arguments.last().type
}

fun getParameterTypeProjectionsFromFunctionType(type: KotlinType): List<TypeProjection> {
    assert(type.isFunctionType) { "Not a function type: $type" }
    val arguments = type.arguments
    val first = if (type.isExtensionFunctionType) 1 else 0
    val last = arguments.size - 1
    assert(first <= last) { "Not an exact function type: $type" }
    return arguments.subList(first, last)
}

fun createFunctionType(
        builtIns: KotlinBuiltIns,
        annotations: Annotations,
        receiverType: KotlinType?,
        parameterTypes: List<KotlinType>,
        returnType: KotlinType
): KotlinType {
    val arguments = getFunctionTypeArgumentProjections(receiverType, parameterTypes, returnType)
    val size = parameterTypes.size
    val classDescriptor = builtIns.getFunction(if (receiverType == null) size else size + 1)

    val typeAnnotations =
            if (receiverType == null || annotations.findAnnotation(KotlinBuiltIns.FQ_NAMES.extensionFunctionType) != null) {
                annotations
            }
            else {
                val extensionFunctionAnnotation = AnnotationDescriptorImpl(
                        builtIns.getBuiltInClassByName(KotlinBuiltIns.FQ_NAMES.extensionFunctionType.shortName()).defaultType,
                        emptyMap(), SourceElement.NO_SOURCE
                )

                // TODO: preserve laziness of given annotations
                AnnotationsImpl(annotations + extensionFunctionAnnotation)
            }

    return KotlinTypeImpl.create(typeAnnotations, classDescriptor, false, arguments)
}

internal fun getFunctionTypeArgumentProjections(
        receiverType: KotlinType?,
        parameterTypes: List<KotlinType>,
        returnType: KotlinType
): List<TypeProjection> {
    fun KotlinType.defaultProjection() = TypeProjectionImpl(Variance.INVARIANT, this)

    val arguments = ArrayList<TypeProjection>(parameterTypes.size + (if (receiverType != null) 1 else 0) + 1)
    receiverType?.let { arguments.add(it.defaultProjection()) }
    parameterTypes.mapTo(arguments, KotlinType::defaultProjection)
    arguments.add(returnType.defaultProjection())
    return arguments
}
