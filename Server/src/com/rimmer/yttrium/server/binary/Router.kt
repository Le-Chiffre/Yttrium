package com.rimmer.yttrium.server.binary

import com.rimmer.yttrium.InvalidStateException
import com.rimmer.yttrium.NotFoundException
import com.rimmer.yttrium.UnauthorizedException
import com.rimmer.yttrium.router.Route
import com.rimmer.yttrium.router.RouteContext
import com.rimmer.yttrium.router.RouteListener
import com.rimmer.yttrium.router.Router
import com.rimmer.yttrium.serialize.*
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext

enum class ResponseCode {
    Success, NoRoute, NotFound, InvalidArgs, NoPermission, InternalError
}

fun routeHash(r: Route) = routeHash(r.name, r.version)
fun routeHash(name: String, version: Int = 0) = name.hashCode() + version * 3452056

class BinaryRouter(
    val router: Router,
    val listener: RouteListener? = null
): (ChannelHandlerContext, ByteBuf, ByteBuf, () -> Unit) -> Unit {
    private val segmentMap = router.routes.associateBy { routeHash(it) }

    override fun invoke(context: ChannelHandlerContext, source: ByteBuf, target: ByteBuf, f: () -> Unit) {
        val id = source.readVarInt()
        val route = segmentMap[id]
        if(route == null) {
            error(ResponseCode.NoRoute, "Route $id not found", target, f)
            return
        }

        val callId = listener?.onStart(route) ?: 0
        try {
            val params = arrayOfNulls<Any>(route.typedSegments.size)
            val queries = arrayOfNulls<Any>(route.queries.size)

            route.typedSegments.forEachIndexed { i, segment ->
                params[i] = readBinary(source, segment.type!!)
            }

            val nullMap = source.readVarLong()
            route.queries.forEachIndexed { i, query ->
                if((nullMap and (1L shl i)) != 0L) {
                    queries[i] = readBinary(source, query.type)
                } else if(query.optional) {
                    queries[i] = query.default
                } else {
                    val description = if(query.description.isNotEmpty()) "(${query.description})" else "(no description)"
                    val type = "of type ${query.type.simpleName}"
                    throw InvalidStateException(
                        "Request to ${route.name} is missing required query parameter \"${query.name}\" $description $type"
                    )
                }
            }

            route.handler(RouteContext(context, params, queries), object: RouteListener {
                override fun onStart(route: Route) = 0L
                override fun onSucceed(id: Long, route: Route, result: Any?) {
                    val writerIndex = target.writerIndex()
                    try {
                        target.writeByte(ResponseCode.Success.ordinal)
                        writeBinary(result, target)
                        f()
                        listener?.onSucceed(callId, route, result)
                    } catch(e: Throwable) {
                        target.writerIndex(writerIndex)
                        mapError(e, target, f)
                    }
                }
                override fun onFail(id: Long, route: Route, reason: Throwable?) {
                    mapError(reason, target, f)
                }
            })
        } catch(e: Throwable) {
            mapError(e, target, f)
        }
    }

    fun mapError(error: Throwable?, target: ByteBuf, f: () -> Unit) = when(error) {
        is InvalidStateException -> error(ResponseCode.InvalidArgs, error.message ?: "bad request", target, f)
        is UnauthorizedException -> error(ResponseCode.NoPermission, error.message ?: "forbidden", target, f)
        is NotFoundException -> error(ResponseCode.NotFound, error.message ?: "not found", target, f)
        else -> error(ResponseCode.InternalError, "internal error", target, f)
    }

    fun error(code: ResponseCode, desc: String, target: ByteBuf, f: () -> Unit) {
        target.writeByte(code.ordinal)
        target.writeString(desc)
        f()
    }
}