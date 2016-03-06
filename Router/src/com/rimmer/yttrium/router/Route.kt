package com.rimmer.yttrium.router

import io.netty.channel.ChannelHandlerContext

/** The supported http call methods. */
enum class HttpMethod {GET, POST, DELETE, PUT}

/**
 * Contains information about a callable route in this server.
 * @param name The route name as it will be displayed in logs, metrics, etc.
 * @param method The HTTP calling method for this route.
 * @param version The route version - this allows using equivalent routes with different parameters.
 * @param segments The route path as it will be called through HTTP.
 * @param typedSegments The path segments that will be bound the a parameter.
 * @param queries Any parameters that aren't provided through the path.
 * @param handler Takes request information for the route and executes it.
 */
class Route(
    val name: String,
    val method: HttpMethod,
    val version: Int,
    val segments: Array<PathSegment>,
    val typedSegments: Array<PathSegment>,
    val queries: Array<RouteQuery>,
    var handler: (RouteContext, RouteListener) -> Unit = {c, r ->}
)

/** Interface that can be used by plugins to modify the signature of a route. */
interface RouteModifier {
    /** The parameter types that need to be provided to the route handler. */
    val parameterTypes: Array<Class<*>>

    /**
     * Indicates that this plugin will provide this parameter to the route handler.
     * It will be ignored by the query parameter builder.
     */
    fun provideParameter(index: Int)

    /**
     * Adds to the calling url of this route.
     * @param segments A list of segments to add - this can contain both string and parameter segments.
     */
    fun addPath(segments: List<PathSegment>)

    /** Adds an additional query parameter. */
    fun addQuery(name: String, type: Class<*>, default: Any? = null, description: String = "")

    /** Returns the index of the first argument of the provided type, or null. */
    fun hasParameter(type: Class<*>): Int? {
        val i = parameterTypes.indexOf(type)
        return if(i == -1) null else i
    }

    /** If a parameter of the requested type exists, provide it and return its index. */
    fun provideIfExists(type: Class<*>): Int? {
        val i = hasParameter(type)
        if(i != null) provideParameter(i)
        return i
    }
}

/**
 * Defines a generic property applied on a route.
 * Properties can be used to send additional data to plugins.
 */
class RouteProperty(val name: String, val value: Any)

/**
 * Represents a query parameter within a route.
 * @param name The parameter name as it appears in the url.
 * @param hash The hash of the parameter name.
 * @param type The type this should be parsed to.
 * @param default If set, the parameter is optional and should be set to this if not provided.
 * @param description The provided description of this parameter.
 */
class RouteQuery(val name: String, val hash: Int, val type: Class<*>, val default: Any?, val description: String)

/**
 * When sent to a route handler, this will be called with progress information about the route.
 * Can be used to collect metrics or display debug information.
 */
interface RouteListener {
    /**
     * This is called whenever a route call starts.
     * @return A listener id that will be sent to any followup calls for this route.
     */
    fun onStart(route: Route): Long

    /**
     * This is called whenever a route call succeeds.
     * @param id The id that was previously returned by onStart.
     * @param result The returned result of the route.
     */
    fun onSucceed(id: Long, route: Route, result: Any?)

    /**
     * This is called whenever a route call fails.
     * @param id The id that was previously returned by onStart.
     * @param reason The reason this call failed, if any.
     */
    fun onFail(id: Long, route: Route, reason: Throwable?)
}

class RouteContext(
    val channel: ChannelHandlerContext,
    val pathParameters: Array<Any?>,
    val queryParameters: Array<Any?>
) {
    fun <T> succeed(v: T) = Future<T>().succeed(v)
    fun succeed() = Future<Unit>().succeed(Unit)

    fun fail(cause: Throwable?) {}
}

class Future<T> {
    fun succeed(v: T): Future<T> {
        then?.invoke(v)
        result = v
        return this
    }

    fun then(f: (T) -> Unit) {
        then = f
        result?.let {f(it)}
    }

    private var then: ((T) -> Unit)? = null
    private var result: T? = null
}
