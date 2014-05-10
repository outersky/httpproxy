package cn.hillwind.app.proxy

import java.io.File
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.concurrent.locks.ReentrantLock
import java.util.Date
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.*
import kotlin.properties.Delegates
import java.util.regex.Pattern

/** execute a function with this as parameter */
fun <T> T.self(f:(x:T)->Unit):T{
    f(this)
    return this
}

/*
fun <T> T.me(f:(x:T)->Unit):T{
    f(this)
    return this
}
*/

/** execute a function with this as receiver */
fun <T> T.me(f:T.()->Unit):T{
    f()
    return this
}

/** if this is null , f() will be returned */
fun <T> T.getOrElse(f:()->T):T{
    return this ?: f()
}

/** if this is not null , execute f() with this as parameter; otherwise null will be returned */
fun <T:Any> T?.nullOrElse(f:(x:T)->T?):T?{
    if(this!=null) {
        f(this)
    }
    return this
}

/** execute f() if this is null */
fun <T> T.ifNull(f:()->Unit):T{
    if(this==null) {
        f()
    }
    return this
}

fun <T> T.When(f:(T)->Boolean,f2:(T)->Unit):T{
    if(f(this)){
        f2(this)
    }
    return this
}

/** execute f() if this is not null */
fun <T:Any> T?.ifNotNull(f:(x:T)->Unit):T?{
    if(this!=null) {
        f(this)
    }
    return this
}

fun Array<Type?>?.c(i:Int):Class<*>?{
    if(this==null) return null
    if(i<0 || i>=this.size) return null
    return this[i] as Class<*>
}

/** string template function: will replace #{name} with replacement[name] */
fun String.richFormat(replacement: Map<String, String>):String{
    var str = this
    for ((k,v) in replacement ){
        str = str.replaceAll("#\\{${k}\\}",v)
    }
    return str
}

private object Hash {
    fun encrypt(data:ByteArray, algorithm:String = "SHA-256") :ByteArray {
        val md = MessageDigest.getInstance(algorithm)
        md.update(data)
        return md.digest()!!
    }
}

/** convert a byte[] to hex string */
fun ByteArray.toHexString():String {
    return this.map { b ->
        (if(b>=0 && b<16) "0" else "") + Integer.toHexString( b.toInt() and 0x00FF)
    } .makeString("")
}

fun ByteArray.hash(algorithm:String = "SHA-256"):ByteArray {
    return Hash.encrypt(this,algorithm)
}

object Random : java.util.Random(){ // SecureRandom() {
}

fun ByteArray.random():ByteArray{
    Random.nextBytes(this)
    return this
}

fun String.hash(algorithm:String = "SHA-256"):String {
    return Hash.encrypt(this.getBytes("utf-8"),algorithm).toHexString()
}
fun String.removeAllBlank():String {
    return this.replaceAll("\\s","")
}

fun BigInteger.toHexString():String{
    return this.toString(16)
}

val formatter = SimpleDateFormat("yyyyMMdd")
val longFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
val formatter2 = SimpleDateFormat("yyyy-MM-dd HH:mm")

fun Date.format():String{
    return formatter.format(this)
}
fun Date.longFormat():String{
    return longFormatter.format(this)
}

/** parse Date with <code>SimpleDateFormat("yyyyMMdd")</code> */
fun String.parseDate():Date{
    return formatter.parse(this)!!
}

/** parse Date with <code>SimpleDateFormat("yyyy-MM-dd HH:mm")</code> */
fun String.parseDateTime():Date{
    return formatter2.parse(this)!!
}

class KThreadLocal<T:Any>(val init:T? = null) : ThreadLocal<T?>(){
    override protected fun initialValue() : T? {
        return init
    }
}

fun <T> Map<String,T>.subMap(prefix:String, removePrefix:Boolean = false):Map<String,T>{
    if(prefix.isEmpty()){
        return this
    }
    val m = hashMapOf<String,T>()
    val size = prefix.size
    this.keySet().filter { it.startsWith(prefix) }.forEach {
        val key = if (removePrefix) it.substring(size) else it
        m.put(key,this.get(it)!!)
    }
    return m
}

fun File.ensureFolder():File {
    if(!this.exists()) this.mkdirs()
    return this
}

/** run later */
public fun defer(millis:Long,action:()->Any?){
    java.lang.Thread(Runnable{
        Thread.sleep(millis)
        action()
    }).start()
}

public fun success(action:()->Any?):Boolean{
    try{
        action()
        return true
    }catch(e:Exception){
        return false
    }
}

/** return <code>action()</code>, if exception occurs, <code>default</code> will be returned */
public fun tryWithDefault<T>(default:T, action:()->T):Pair<T,Exception?>{
    try{
        return Pair(action(),null)
    }catch(e:Exception){
        return Pair(default,e)
    }
}

/** return <code>action()</code>, if exception occurs, <code>default()</code> will be returned */
public fun tryWithDefault<T>(default:()->T, action:()->T):Pair<T,Exception?>{
    try{
        return Pair(action(),null)
    }catch(e:Exception){
        return Pair(default(),e)
    }
}

public fun <T> retry(retryTimes:Int=3,action:()->T):T{
    var exp:Exception? = null
    for(i in 1..retryTimes){
        try{
            return action()
        }catch(e:Exception){
            exp = e
        }
    }

    throw exp!!
}

public data class ProcessResult(var output:String,var error:String,var exception:Exception?)

public fun waitProcess(process:Process):ProcessResult{
    var output = ""
    var error = ""
    var exception:Exception? = null
    run { //():Unit ->
        val fis = process.getInputStream()!!
        output = fis.reader().readText()
        fis.close()
        //return@run  // test return@run method
    }
    run{
        val fis = process.getErrorStream()!!
        error = fis.reader().readText()
        fis.close();
    }
    try {
        process.waitFor();
    } catch ( e : Exception ) {
        exception = e
    }
    return ProcessResult(output,error,exception)
}

public class Regex(val pattern:String, val input:String, val flags:Int=0) : Iterator<String>{

    val p = Pattern.compile(pattern,flags)
    val m = p.matcher(input)

    val success = m.matches()
    val size = if(success) m.groupCount()+1 else 0

    private var index:Int = -1

    override fun next(): String {
        return m.group(++index)!!
    }

    override fun hasNext(): Boolean {
        return success && index+1< size
    }

    fun get(i:Int):String{
        return m.group(i)!!
    }

}
