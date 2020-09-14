/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle

import java.io.File
import java.io.Serializable

typealias CachedArgumentIdType = Int
typealias ClasspathArgumentCacheIdType = Array<Int>

interface CachedArgsInfo : Serializable {
    val currentCachedCompilerArgumentsBucket: CachedCompilerArgumentsBucket
    val defaultCachedCompilerArgumentsBucket: CachedCompilerArgumentsBucket
    val dependencyClasspathCacheIds: ClasspathArgumentCacheIdType
}

class CachedCompilerArgumentsBucket private constructor(
    val cachedGeneralArguments: Array<Int>,
    val cachedClasspath: Array<Int>,
    val cachedPluginClasspath: Array<Int>,
    val cachedFriendPaths: Array<Int>
) : Serializable {
    constructor(otherBucket: CachedCompilerArgumentsBucket) : this(
        arrayOf(*otherBucket.cachedGeneralArguments),
        arrayOf(*otherBucket.cachedClasspath),
        arrayOf(*otherBucket.cachedPluginClasspath),
        arrayOf(*otherBucket.cachedFriendPaths)
    )

    fun collectArgumentsList(mapper: ICompilerArgumentsMapper): List<String> {
        val res = cachedGeneralArguments.map { mapper.getCommonArgument(it) } +
                "$PLUGIN_CLASSPATH_PREFIX${mapper.getClasspathArgument(cachedPluginClasspath, ",")}" +
                "$FRIEND_PATH_PREFIX${mapper.getClasspathArgument(cachedPluginClasspath, ",")}"
        return res.toMutableList().apply {
            mapper.getClasspathArgument(cachedClasspath, File.pathSeparator).takeIf { it.isNotEmpty() }?.let { listOf("-classpath", it) }
                ?.also { addAll(it) }
        }
    }

    companion object {
        const val FRIEND_PATH_PREFIX = "-Xfriend-paths="
        const val PLUGIN_CLASSPATH_PREFIX = "-Xplugin="
        val classpathArgPointers = setOf("-classpath", "-cp")

        fun parseBucketFromArguments(arguments: List<String>, mapper: ICompilerArgumentsMapper): CachedCompilerArgumentsBucket {

            val pluginClasspathArgument = arguments.firstOrNull { it.startsWith(PLUGIN_CLASSPATH_PREFIX) }
            val friendPathsArgument = arguments.firstOrNull { it.startsWith(FRIEND_PATH_PREFIX) }
            val classpathArguments = arguments.flatMapIndexed { index, s ->
                if (s in classpathArgPointers) listOf(s, arguments[index + 1]) else emptyList()
            }

            // TODO(ychernyshev) Does the order of arguments required here?
            val cachedGeneralArguments =
                (arguments - pluginClasspathArgument - friendPathsArgument - classpathArguments - classpathArgPointers)
                    .filterNotNull()
                    .map { mapper.cacheCommonArgument(it) }
                    .toTypedArray()

            val cachedClasspath = classpathArguments.lastOrNull()
                ?.let { mapper.cacheClasspathArgument(it, File.pathSeparator) } ?: emptyArray()

            val cachedPluginClasspath =
                pluginClasspathArgument?.removePrefix(PLUGIN_CLASSPATH_PREFIX)?.let { mapper.cacheClasspathArgument(it, ",") }
                    ?: emptyArray()
            val cachedFriendPaths =
                friendPathsArgument?.removePrefix(FRIEND_PATH_PREFIX)?.let { mapper.cacheClasspathArgument(it, ",") } ?: emptyArray()

            return CachedCompilerArgumentsBucket(cachedGeneralArguments, cachedClasspath, cachedPluginClasspath, cachedFriendPaths)
        }

    }
}

class CachedArgsInfoImpl(
    override val currentCachedCompilerArgumentsBucket: CachedCompilerArgumentsBucket,
    override val defaultCachedCompilerArgumentsBucket: CachedCompilerArgumentsBucket,
    override val dependencyClasspathCacheIds: ClasspathArgumentCacheIdType
) : CachedArgsInfo {
    constructor(cachedArgsInfo: CachedArgsInfo) : this(
        CachedCompilerArgumentsBucket(cachedArgsInfo.currentCachedCompilerArgumentsBucket),
        CachedCompilerArgumentsBucket(cachedArgsInfo.defaultCachedCompilerArgumentsBucket),
        arrayOf(*cachedArgsInfo.dependencyClasspathCacheIds)
    )
}

typealias CachedCompilerArgumentBySourceSet = Map<String, CachedArgsInfo>

/**
 * Creates deep copy in order to avoid holding links to Proxy objects created by gradle tooling api
 */
fun CachedCompilerArgumentBySourceSet.deepCopy(): CachedCompilerArgumentBySourceSet {
    val result = HashMap<String, CachedArgsInfo>()
    this.forEach { key, value -> result[key] = CachedArgsInfoImpl(value) }
    return result
}

interface ICompilerArgumentsMapper : Serializable {
    fun getArgumentCache(argument: String): Int?
    fun cacheCommonArgument(commonArgument: String): Int
    fun getCommonArgument(id: Int): String
    fun cacheClasspathArgument(classpathArgument: String, separator: String): Array<Int>
    fun getClasspathArgument(ids: Array<Int>, separator: String): String
    fun clear()
    fun copyCache(): Map<Int, String>
}

open class CompilerArgumentsMapper(val initialId: Int = 0) : ICompilerArgumentsMapper {

    constructor(otherMapper: CompilerArgumentsMapper) : this(otherMapper.initialId) {
        idToCompilerArguments.putAll(otherMapper.idToCompilerArguments)
        compilerArgumentToId.putAll(otherMapper.compilerArgumentToId)
        nextId = otherMapper.nextId
    }

    protected var nextId = initialId
    protected val idToCompilerArguments: MutableMap<Int, String> = mutableMapOf()
    protected val compilerArgumentToId: MutableMap<String, Int> = mutableMapOf()

    override fun getArgumentCache(argument: String): Int? = compilerArgumentToId[argument]

    override fun cacheCommonArgument(commonArgument: String): Int = getArgumentCache(commonArgument)
        ?: run {
            val index = nextId++
            idToCompilerArguments[index] = commonArgument
            compilerArgumentToId[commonArgument] = index
            index
        }

    override fun getCommonArgument(id: Int): String = idToCompilerArguments[id]!!

    override fun cacheClasspathArgument(classpathArgument: String, separator: String): Array<Int> =
        classpathArgument.split(separator).map { cacheCommonArgument(it) }.toTypedArray()

    override fun getClasspathArgument(ids: Array<Int>, separator: String): String =
        ids.joinToString(separator) { getCommonArgument(it) }

    override fun clear() {
        idToCompilerArguments.clear()
        compilerArgumentToId.clear()
        nextId = initialId
    }

    override fun copyCache(): Map<Int, String> = HashMap(idToCompilerArguments)
}

interface IDetachableMapper : ICompilerArgumentsMapper {
    val masterMapper: ICompilerArgumentsMapper
    fun detach(): ICompilerArgumentsMapper
}

class DetachableCompilerArgumentsMapper(override val masterMapper: ICompilerArgumentsMapper) : CompilerArgumentsMapper(),
    IDetachableMapper {
    override fun cacheCommonArgument(commonArgument: String): Int =
        masterMapper.getArgumentCache(commonArgument) ?: masterMapper.cacheCommonArgument(commonArgument).also {
            idToCompilerArguments[it] = commonArgument
            compilerArgumentToId[commonArgument] = it
        }

    override fun detach(): ICompilerArgumentsMapper = CompilerArgumentsMapper(this)
}

interface IWithCheckoutMapper : ICompilerArgumentsMapper {
    fun checkoutMapper(): IDetachableMapper
}

class CompilerArgumentsMapperWithCheckout : CompilerArgumentsMapper(), IWithCheckoutMapper {
    override fun checkoutMapper(): IDetachableMapper = DetachableCompilerArgumentsMapper(this)
}

interface IWithMergeMapper : ICompilerArgumentsMapper {
    fun mergeMapper(mapper: ICompilerArgumentsMapper)
}

class CompilerArgumentsMapperWithMerge : CompilerArgumentsMapper(), IWithMergeMapper {

    override fun mergeMapper(mapper: ICompilerArgumentsMapper) {
        mapper.copyCache().forEach { (id, arg) ->
            idToCompilerArguments[id] = arg
            compilerArgumentToId[arg] = id
        }
    }
}

data class CompilerArgumentMappersContainer @JvmOverloads constructor(
    val projectCompilerArgumentsMapper: CompilerArgumentsMapperWithMerge = CompilerArgumentsMapperWithMerge(),
    val projectMppCompilerArgumentsMapper: CompilerArgumentsMapperWithMerge = CompilerArgumentsMapperWithMerge()
) {
    @JvmOverloads
    fun mergeArgumentsFromMapper(mapper: ICompilerArgumentsMapper, isMpp: Boolean = false) {
        if (isMpp) projectMppCompilerArgumentsMapper.mergeMapper(mapper) else projectCompilerArgumentsMapper.mergeMapper(mapper)
    }
}
