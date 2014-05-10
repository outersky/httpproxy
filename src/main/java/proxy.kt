package cn.hillwind.app.proxy

import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.util.Date
import java.util.Arrays
import java.util.zip.GZIPInputStream

private val DEFAULT_TIMEOUT = 20 * 1000
private val DEFAULT_DEBUG_LEVEL = 0
private val DEFAULT_PORT = 18888

private var debugLevel: Int = DEFAULT_DEBUG_LEVEL
private var debugOut: PrintStream = System.out

private fun log(msg:String, exception:Exception?=null){
    if(debugLevel>0) {
        debugOut.println("${msg}")
        if(exception!=null) {
            exception.printStackTrace(debugOut)
        }
    }
}

/* here's a main method, in case you want to run this by itself */
public fun main(args: Array<String>) {
    Db.init()

    var port = DEFAULT_PORT
    var fwdProxyServer = ""
    var fwdProxyPort = 0
    var timeout = DEFAULT_TIMEOUT

    if (args.size < 0) {
        System.err.println("USAGE: java jProxy <port number> [<fwd proxy> <fwd port>]")
        System.err.println("  <port number>   the port this service listens on")
        System.err.println("  <fwd proxy>     optional proxy server to forward requests to")
        System.err.println("  <fwd port>      the port that the optional proxy server is on")
        System.err.println("\nHINT: if you don't want to see all the debug information flying by,")
        System.err.println("you can pipe the output to a file or to 'nul' using \">\". For example:")
        System.err.println("  to send output to the file prox.txt: java jProxy 8080 > prox.txt")
        System.err.println("  to make the output go away: java jProxy 8080 > nul")
        return
    }

    if (args.size > 2) {
        port = Integer.parseInt(args[0])
        fwdProxyServer = args[1]
        fwdProxyPort = Integer.parseInt(args[2])
    }

    System.err.println("  **  Starting jProxy on port " + port + ". Press CTRL-C to end.  **\n")
    val jp = jProxy(port, fwdProxyServer, fwdProxyPort, timeout)
    jp.start()

}

public class jProxy(var port: Int, var proxyServer: String, var proxyPort: Int, var timeout: Int) : Thread() {

    private var server: ServerSocket? = null
    private var fwdServer: String = ""
    private var fwdPort: Int = 0

    /* return whether or not the socket is currently open  */
    public fun isRunning(): Boolean {
        return server != null
    }

    /* closeSocket will close the open ServerSocket; use this
     * to halt a running jProxy thread
     */
    public fun closeSocket() {
        try {
            server?.close()
        } catch (e: Exception) {
            log("close socker error",e)
        }
        server = null
    }

    override fun run() {
        try {
            // create a server socket, and loop forever listening for
            // client connections
            server = ServerSocket(port)
            log("Started jProxy on port " + port)

            while (true) {
                val client = server?.accept()!!
                val t = ProxyThread(client, fwdServer, fwdPort,timeout)
                t.start()
            }
        } catch (e: Exception) {
            log("jProxy Thread error: " , e)
        }

        closeSocket()
    }

}

/*
 * The ProxyThread will take an HTTP request from the client
 * socket and send it to either the server that the client is
 * trying to contact, or another proxy server
 */
class ProxyThread(val socket: Socket, val fwdServer: String, val fwdPort: Int, val timeout:Int) : Thread() {
    private val begin = System.currentTimeMillis()

    override fun run() {
        try {
            // client streams (make sure you're using streams that use
            // byte arrays, so things like GIF and JPEG files and file
            // downloads will transfer properly)
            val clientIn = BufferedInputStream(socket.getInputStream()!!)
            val clientOut = BufferedOutputStream(socket.getOutputStream()!!)

            // the socket to the remote server
            var server:Socket? = null

            // other variables
            val host = StringBuffer("")
            val request = getHTTPData(clientIn, host)
            var response:ByteArray? = null
            val requestLength = request.size
            var responseLength = 0
            var hostName = host.toString()
            val pos = hostName.indexOf(":")
            var hostPort = 80

            // get the header info (the web browser won't disconnect after
            // it's sent a request, so make sure the waitForDisconnect
            // parameter is false)

            // separate the host name from the host port, if necessary
            // (like if it's "servername:8000")
            if (pos > 0) {
                hostPort = Integer.parseInt(hostName.substring(pos + 1).trim())
                hostName = hostName.substring(0, pos).trim()
            }

            log("prepare connectting to remote host")
            // either forward this request to another proxy server or
            // send it straight to the Host
            try {
                if ((fwdServer.length() > 0) && (fwdPort > 0)) {
                    server = Socket(fwdServer, fwdPort)
                } else {
                    server = Socket(hostName, hostPort)
                }
            } catch (e: Exception) {
                // tell the client there was an error
                val errMsg = "HTTP/1.0 500\nContent Type: text/plain\n\n" + "Error connecting to the server:\n" + e + "\n"
                clientOut.write(errMsg.getBytes(), 0, errMsg.length())
            }

            if (server != null) {
                server?.setSoTimeout(timeout)
                val serverIn = BufferedInputStream(server?.getInputStream()!!)
                val serverOut = BufferedOutputStream(server?.getOutputStream()!!)

                // send the request out
                serverOut.write(request, 0, requestLength)
                log("request forwarded to remote host!")
                serverOut.flush()

                response = getHTTPData(serverIn)
                responseLength = response?.size ?: 0

                serverIn.close()
                serverOut.close()
            }

            // send the response back to the client, if we haven't already
            clientOut.write(response!!, 0, responseLength)

            // if the user wants debug info, send them debug info; however,
            // keep in mind that because we're using threads, the output won't
            // necessarily be synchronous
            log("\n\nRequest from " + socket.getInetAddress()?.getHostAddress() + " on Port " + socket.getLocalPort() + " to host " + hostName + ":" + hostPort + "\n  (" + requestLength + " bytes sent, " + responseLength + " bytes returned, " + (System.currentTimeMillis() - begin) + " ms elapsed)")

            HttpEntity.save(HttpEntity.parse(request,response) me { startTime = Date(begin) } )

            // close all the client streams so we can listen again
            clientOut.close()
            clientIn.close()
            socket.close()
        } catch (e: Exception) {
            log("Error in ProxyThread: ", e)
        }

    }

    private fun getHTTPData(stream: InputStream): ByteArray {
        // get the HTTP data from an InputStream, and return it as
        // a byte array
        // the waitForDisconnect parameter tells us what to do in case
        // the HTTP header doesn't specify the Content-Length of the
        // transmission
        val foo = StringBuffer("")
        return getHTTPData(stream, foo)
    }

    private fun getHTTPData(stream: InputStream, host: StringBuffer): ByteArray {
        // get the HTTP data from an InputStream, and return it as
        // a byte array, and also return the Host entry in the header,
        // if it's specified -- note that we have to use a StringBuffer
        // for the 'host' variable, because a String won't return any
        // information when it's used as a parameter like that
        val bs = ByteArrayOutputStream()
        streamHTTPData(stream, bs, host)
        return bs.toByteArray()
    }

    private fun streamHTTPData(stream: InputStream, out: OutputStream): Int {
        val foo = StringBuffer("")
        return streamHTTPData(stream, out, foo)
    }

    private fun streamHTTPData(stream: InputStream, out: OutputStream, host: StringBuffer): Int {
        // get the HTTP data from an InputStream, and send it to
        // the designated OutputStream
        val header = StringBuffer("")
        var data:String?
        var responseCode = 0
        var contentLength = 0
        var byteCount = 0

        var chunk = false

        try {
            // get the first line of the header, so we know the response code
            data = readLine(stream)
            if (data != null) {
                header.append(data + "\r\n")
                Regex("""^http\S*\s+(\S+)\s+.*""",data!!.toLowerCase()).When({it.success}){
                    responseCode = Integer.parseInt(it[1].trim())
                }
            }

            // get the rest of the header info
            while (true) {
                data = readLine(stream)

                // // the header ends at the first blank line
                if(data==null || data!!.length() == 0) {
                    break
                }

                header.append(data + "\r\n")

                val line = data!!.toLowerCase()

                // check for the Host header
                Regex("""^host:(.+)""",line).When({it.success}) {
                    host.setLength(0)
                    host.append(it[1].trim())
                }

                // check for the Content-Length header
                Regex("""^content-length:(.+)""",line).When({it.success}){
                    contentLength = Integer.parseInt(it[1].trim())
                }

                // check for the Transfer-Encoding header
                Regex("""^transfer-encoding:.*chunked.*""",line).When({it.success}){
                    chunk = true
                }

            }

            // add a blank line to terminate the header info
            header.append("\r\n")

            // convert the header to a byte array, and write it to our stream
            out.write(header.toString().getBytes(), 0, header.length())

            // if the header indicated that this was not a 200 response,
            // just return what we've got if there is no Content-Length,
            // because we may not be getting anything else
            if ((responseCode != 200) && (contentLength == 0)) {
                out.flush()
                return header.length()
            }

            // get the body, if any; we try to use the Content-Length header to
            // determine how much data we're supposed to be getting, because
            // sometimes the client/server won't disconnect after sending us
            // information...
            if (contentLength > 0) {
                chunk = false
            }

            byteCount = if(chunk){
                readChunk(stream,out)
            }else{
                read(stream,contentLength,out)
            }
        } catch (e: Exception) {
            log("Error getting HTTP data: " , e)
        }

        //flush the OutputStream and return
        try {
            out.flush()
        } catch (e: Exception) {
        }

        return header.length() + byteCount
    }

    private fun read(stream:InputStream, length:Int,out: OutputStream):Int{
        val buf = ByteArray(4096)
        var bytesIn = 0
        var total = 0
        while ( total < length && ( {bytesIn = stream.read(buf); bytesIn}() >= 0 ) ) {
            out.write(buf, 0, bytesIn)
            total += bytesIn
        }
        return total
    }

    val chunkEnd = "0\r\n\r\n".getBytes()
    private fun readChunk(stream:InputStream, out: OutputStream):Int{
        val buf = ByteArray(4096)
        var total:Int=0
        var bytesIn:Int
        while ( true ) {
            bytesIn = stream.read(buf);
            if(bytesIn<0){
                break;
            }
            total += bytesIn
            out.write(buf, 0, bytesIn)
            if(testChunkEnd(buf,bytesIn)){
                break
            }
        }
        return total
    }

    private fun testChunkEnd(buf : ByteArray, len:Int):Boolean{
        var i = 4;
        var ended = true;
        if(len<5){
            return false;
        }
        while(i>=0 && len >=5 ){
            if(chunkEnd[i]!=buf[len-5+i]){
                ended = false;
                break;
            }
            i--
        }
        log("testChunkEnd:${ended}")
        return ended
    }

    private fun readLine(stream: InputStream): String? {
        // reads a line of text from an InputStream
        val data = StringBuffer("")
        var c: Int = 0

        try {
            // if we have nothing to read, just return null
            stream.mark(1)
            if (stream.read() == -1) {
                return null
            }else {
                stream.reset()
            }

            while (  { c = stream.read(); c>0 && c != 10 && c != 13 }() ) {
                data.append(c.toChar())
            }

            // deal with the case where the end-of-line terminator is \r\n
            if (c == 13) {
                stream.mark(1)
                if (stream.read() != 10) {
                    stream.reset()
                }
            }
        } catch (e: Exception) {
            log("Error getting header: " , e)
        }

        // and return what we have
        return data.toString()
    }

}

public class HttpEntity {
    var id:Long=0
    var method:String=""
    var host:String=""
    var url:String=""
    var contentType:String=""
    var contentEncoding:String=""
    var status:Int=0
    var statusStr=""
    var length:Long=0
    var startTime:Date = EMPTY_DATE
    var requestBody:ByteArray?=null
    var content:ByteArray?=null

    var transferEncoding:String=""

    var requestHeader:String=""
    var responseHeader:String=""


    override fun toString(): String {
        return """host:${host}
method:${method}
url:${url}

status:${status}
statusStr:${statusStr}
contentType:${contentType}
contentEncoding:${contentEncoding}
"""

    }

    fun realContent():ByteArray? {
        if(content==null){ return null}
        var cnt = content!!
        if(contentEncoding.indexOf("gzip")>=0){
            cnt = GZIPInputStream(cnt.inputStream).readBytes()
        }
        return cnt
    }

    fun dump(output:OutputStream){
        realContent()?.inputStream?.copyTo(output)
    }

    class object {
        private val EMPTY_DATE = Date(0)

        var rdm = 1
        fun save(he:HttpEntity){
            Db.save(he)
        }

        fun parse(request:ByteArray, response:ByteArray?):HttpEntity {

            val he = HttpEntity()
            parseRequest(HttpHeadSlice(request),he)
            if(response!=null) {
                parseResponse(HttpHeadSlice(response), he)
                if(he.transferEncoding.indexOf("chunked")>=0){
                    parseChunkContent(he)
                }
            }

            return he
        }

        fun parseRequest(request: HttpHeadSlice, he:HttpEntity):HttpEntity{
            val firstLine = request.nextLine() ?: ""
            val sb = StringBuilder(firstLine  + "\r\n")
            firstRequestLine(firstLine.trim(), he)
            for(line in request){
                handleHeadItem(line,he)
                sb.append(line).append("\r\n")
            }
            he.requestHeader = sb.toString()
            he.requestBody = request.restBytes()
            return he
        }

        fun parseResponse(response: HttpHeadSlice, he:HttpEntity):HttpEntity{
            val firstLine = response.nextLine()?: ""
            val sb = StringBuilder(firstLine + "\r\n")
            firstResponseLine(firstLine.trim(), he)
            for(line in response){
                handleHeadItem(line,he)
                sb.append(line).append("\r\n")
            }
            he.responseHeader = sb.toString()
            he.content = response.restBytes()
            return he
        }

        fun parseChunkContent(he:HttpEntity){
            val bytes = HttpHeadSlice(he.content!!)
            var result = ByteArray(0)
            while(true) {
                val len = Integer.parseInt(bytes.nextLine()!!, 16)
                if(len==0){
                    break;
                }
                val b2 = bytes.next(len)
                val old = result
                result = ByteArray(result.size + len)
                System.arraycopy(old,0,result,0,old.size)
                System.arraycopy(b2,0,result,old.size,len)
                bytes.skip(2)
            }
            he.content = result
            he.length = he.content!!.size.toLong()
            he.content!!.inputStream.copyTo(FileOutputStream("/tmp/proxy/${++rdm}.${he.contentType.replaceAll("/","_")}.${he.contentEncoding}"))
        }

        fun firstRequestLine(line:String, he:HttpEntity){
            Regex("""(\S+)\s+(\S+)\s+(.*)""",line).When({it.success}) {
                he.method = it[1]
                he.url = it[2]
            }
        }

        fun firstResponseLine(line:String, he:HttpEntity){
            Regex("""HTTP\S*\s+(\d+)\s+(.*)""",line).When({it.success}) {
                he.status = it[1].toInt()
                if (it.size > 2) {
                    he.statusStr = it[2]
                }
            }
        }

        fun handleHeadItem(line:String, he:HttpEntity){
            val arr = line.split(':')
            arr[0] = arr[0].trim().toLowerCase()
            arr[1] = arr[1].trim()
            when(arr[0]){
                "host" -> he.host = arr[1]
                "content-type" -> he.contentType = arr[1]
                "content-encoding" -> he.contentEncoding = arr[1]
                "content-length" -> he.length = arr[1].toLong()
                "transfer-encoding" -> he.transferEncoding = arr[1]
                else -> ""
            }
        }

    }

}

class HttpHeadSlice(val stream:ByteArray, val offset:Int=0, val length:Int=stream.size) : Iterator<String> {
    private val total = stream.size
    private var index = 0

    override public fun next(): String {
        return nextLine()!!
    }

    

    // HTTP head ends at a blank line.
    override public fun hasNext(): Boolean {
        val c = stream[offset + index].toInt()
        return !( (c == 0) || (c == 10) || (c == 13) )
    }

    public fun nextLine():String?{
        var i = index
        var c:Int = 0

        while ( i < length && offset + i < total ) {
            c = stream[offset + i].toInt()
            // check for an end-of-line character
            if ((c == 0) || (c == 10) || (c == 13)) {
                break
            }
            i++
        }
        var str = if(i>index) String(stream,index, i-index) else null
        if(c==13 && offset + i + 1 < total && stream[offset + i + 1].toInt()==10){
            i ++
        }
        index = i+1
        return str
    }

    public fun next(size:Int):ByteArray{
        val bytes = ByteArray(size)
        System.arraycopy(stream,index,bytes,0,size)
        index = index + size
        return bytes
    }

    public fun skip(size:Int){
        index = index + size
    }

    public fun rest():InputStream = ByteArrayInputStream(stream,index+2,stream.size - index - 2)  // 2 -> skip the blank line

    public fun restBytes():ByteArray {
        val bytes = ByteArray(stream.size - index - 2)  // 2 -> skip the blank line
        System.arraycopy(stream,index + 2,bytes,0,bytes.size)
        return bytes
    }

}
