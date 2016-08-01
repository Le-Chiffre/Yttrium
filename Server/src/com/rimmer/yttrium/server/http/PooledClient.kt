package com.rimmer.yttrium.server.http

import com.rimmer.yttrium.Context
import com.rimmer.yttrium.Task
import com.rimmer.yttrium.getOrAdd
import com.rimmer.yttrium.parseInt
import com.rimmer.yttrium.server.ServerContext
import io.netty.buffer.ByteBuf
import io.netty.channel.EventLoop
import io.netty.handler.codec.http.*
import io.netty.util.AsciiString
import java.util.*

class HttpPooledClient(context: ServerContext) {
    private val pools: Map<EventLoop, HashMap<String, SingleThreadPool>>

    init {
        // We initialize the pool at creation to avoid synchronization when lazily creating pools.
        val map = HashMap<EventLoop, HashMap<String, SingleThreadPool>>()
        context.handlerGroup.forEach { loop ->
            if(loop is EventLoop) {
                map[loop] = HashMap()
            }
        }

        pools = map
    }

    fun get(context: Context, url: String, headers: HttpHeaders? = null, listener: HttpListener? = null) =
        request(context, url, HttpMethod.GET, headers, listener = listener)

    fun post(
        context: Context,
        url: String,
        headers: HttpHeaders? = null,
        body: Any? = null,
        contentType: AsciiString? = HttpHeaderValues.APPLICATION_JSON,
        listener: HttpListener? = null
    ) = request(context, url, HttpMethod.POST, headers, body, contentType, listener)

    fun put(
        context: Context,
        url: String,
        headers: HttpHeaders? = null,
        body: Any? = null,
        contentType: AsciiString? = HttpHeaderValues.APPLICATION_JSON,
        listener: HttpListener? = null
    ) = request(context, url, HttpMethod.PUT, headers, body, contentType, listener)

    fun delete(
        context: Context,
        url: String,
        headers: HttpHeaders? = null,
        body: Any? = null,
        contentType: AsciiString? = HttpHeaderValues.APPLICATION_JSON,
        listener: HttpListener? = null
    ) = request(context, url, HttpMethod.DELETE, headers, body, contentType, listener)

    fun request(
        context: Context,
        url: String,
        method: HttpMethod,
        headers: HttpHeaders? = null,
        body: Any? = null,
        contentType: AsciiString? = HttpHeaderValues.APPLICATION_JSON,
        listener: HttpListener? = null
    ): Task<HttpResult> {
        val loop = context.eventLoop
        val pool = pools[loop] ?:
            throw IllegalArgumentException("Invalid context event loop - the event loop must be in the server's handlerGroup.")

        val split = url.split("://")
        val domain: String
        val path: String
        val isSsl: Boolean

        if(split.size > 1) {
            isSsl = split[0] == "https"
            domain = split[1].substringBefore('/')
            path = split[1].substring(domain.length)
        } else {
            isSsl = false
            domain = split[0].substringBefore('/')
            path = split[0].substring(domain.length)
        }

        val portString = domain.substringAfter(':', "")
        val port = if(portString.isNotEmpty()) parseInt(portString) else if(isSsl) 443 else 80

        val selector = "$domain $isSsl"


        val client = pool.getOrAdd(selector) { SingleThreadPool(PoolConfiguration(4)) {
            connectHttp(loop, domain, port, isSsl, 30000, it)
        } }

        val request = if(body === null) {
            DefaultFullHttpRequest(HttpVersion.HTTP_1_1, method, path)
        } else {
            DefaultHttpRequest(HttpVersion.HTTP_1_1, method, path)
        }

        val httpHeaders = request.headers()
        if(headers !== null) {
            httpHeaders.add(headers)
        }

        if(!httpHeaders.contains(HttpHeaderNames.HOST)) {
            httpHeaders[HttpHeaderNames.HOST] = domain
        }

        if(body !== null) {
            if(!httpHeaders.contains(HttpHeaderNames.CONTENT_TYPE)) {
                httpHeaders[HttpHeaderNames.CONTENT_TYPE] = contentType
            }

            if(!httpHeaders.contains(HttpHeaderNames.CONTENT_LENGTH) && body is ByteBuf) {
                httpHeaders[HttpHeaderNames.CONTENT_LENGTH] = body.writerIndex()
            }
        }

        val task = Task<HttpResult>()
        client.get { c, e ->
            if(e == null) {
                c!!.request(request, body, wrappedListener(task, listener, c))
            } else {
                task.fail(e)
            }
        }
        return task
    }
}