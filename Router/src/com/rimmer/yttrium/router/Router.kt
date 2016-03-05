package com.rimmer.yttrium.router

import com.rimmer.yttrium.router.plugin.Plugin
import java.util.*

class RoutePlugin(val plugin: Plugin<in Any>, val context: Any)

class Router(val plugins: List<Plugin<in Any>>) {
    private val routes = ArrayList<Route>()
    private val swagger = Swagger()
    private var currentCategory = swagger.addCategory("None")

    fun category(name: String, paths: Router.() -> Unit) {
        val oldCategory = currentCategory
        currentCategory = swagger.addCategory(name)
        paths()
        currentCategory = oldCategory
    }

    fun addRoute(desc: RouteBuilder, call: RouteContext.(Array<Any?>) -> Future<in Any>, types: Array<Class<*>>, result: Class<*>) {
        val path = desc.path
        val method = desc.method
        val version = desc.version

        val segments = buildSegments(path, types).toMutableList()
        val queries = ArrayList<RouteQuery>()
        val providers = Array(types.size) { false }

        val modifier = object: RouteModifier {
            override val parameterTypes: Array<Class<*>> get() = types
            override fun provideParameter(index: Int) { providers[index] = true }
            override fun addPath(s: List<PathSegment>) { segments.addAll(s) }
            override fun addQuery(query: RouteQuery) { queries.add(query) }
        }

        // Find the plugins that will be applied to this route.
        val usedPlugins = ArrayList<RoutePlugin>()
        for(p in plugins) {
            val context = p.isUsed(modifier, result, desc.properties)
            if(context != null) {
                usedPlugins.add(RoutePlugin(p, context))
                p.modifyRoute(context, modifier)
            }
        }

        // Create the swagger route.
        val swaggerRoute = Swagger.Route(
            Swagger.PathInfo(buildSwaggerPath(segments), buildEquivalencePath(segments), currentCategory.name),
            method, version
        )
        swagger.addRoute(swaggerRoute, currentCategory)

        // Apply the plugins to swagger.
        for(p in usedPlugins) {
            p.plugin.modifySwagger(p.context, swaggerRoute)
        }

        var argIndex = 0

        // Returns the next handler argument that has no provider yet.
        val nextArg = {
            while(providers[argIndex]) {
                argIndex++
                if(argIndex >= providers.size) {
                    throw IllegalArgumentException("The handler for this route has less arguments than there are in the route")
                }
            }
            argIndex
        }

        // Create a list of path parameter -> handler parameter bindings.
        val pathBindings = ArrayList<Int>()
        for(s in segments) {
            if(s.type != null) {
                pathBindings.add(nextArg())
                swaggerRoute.parameters.add(
                    Swagger.Parameter(s.name, Swagger.ParameterType.Path, "", types[argIndex], false)
                )
            }
        }

        // Create a list of query parameter -> handler parameter bindings.
        val queryBindings = ArrayList<Int>()
        for(q in desc.queries) {
            queryBindings.add(nextArg())
            queries.add(RouteQuery(q.name, types[argIndex], q.default, q.description))
            swaggerRoute.parameters.add(
                Swagger.Parameter(q.name, Swagger.ParameterType.Query, q.description, types[argIndex], q.default != null)
            )
        }

        // Create the route handler.
        val name = "$method ${swaggerRoute.info.path}"
        val route = Route(name, method, version, segments, queries)
        route.handler = routeHandler(
            route,
            usedPlugins,
            pathBindings.toIntArray(),
            queryBindings.toIntArray(),
            types.size,
            call
        )
        routes.add(route)
    }

    fun get(path: String, version: Int = 0) = RouteBuilder(this, HttpMethod.GET, path, version)
    fun post(path: String, version: Int = 0) = RouteBuilder(this, HttpMethod.POST, path, version)
    fun delete(path: String) = RouteBuilder(this, HttpMethod.DELETE, path, 0)
    fun put(path: String) = RouteBuilder(this, HttpMethod.PUT, path, 0)
}