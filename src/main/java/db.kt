package cn.hillwind.app.proxy

import cn.hillwind.app.proxy.HttpEntity
import java.sql.DriverManager
import java.sql.Connection
import java.sql.Statement
import java.sql.Timestamp
import java.io.StringReader
import java.sql.PreparedStatement
import java.sql.ResultSet

object Db {

    private val conn = connect("~/proxy_db")

    private fun connect(url:String) : Connection {
        Class.forName("org.h2.Driver")
        return DriverManager.getConnection("jdbc:h2:${url};AUTO_SERVER=TRUE", "sa", "")
    }

    public fun init(){
        conn.setAutoCommit(false)
        checkTable()
    }

    private fun checkTable(){
        var stmt = conn.createStatement()!!
        stmt.once{
            executeUpdate("""
                CREATE TABLE IF NOT EXISTS HTTP_ENTITY (
                   ID IDENTITY PRIMARY KEY,
                   METHOD VARCHAR_CASESENSITIVE(255),
                   HOST VARCHAR_CASESENSITIVE(255),
                   URL VARCHAR(2048),
                   CONTENT_TYPE VARCHAR_CASESENSITIVE(255),
                   CONTENT_ENCODING VARCHAR_CASESENSITIVE(255),
                   STATUS INT,
                   LENGTH BIGINT,
                   START_TIME TIMESTAMP,
                   REQUEST_HEADER CLOB,
                   RESPONSE_HEADER CLOB,
                   CONTENT BLOB
                )
            """)
        }
    }

    private fun Statement.once(f:Statement.()->Unit){
        try {
            this.f()
            conn.commit()
        }catch(e:Exception){
            e.printStackTrace()
            conn.rollback()
            this.close()
        }finally{
        }
    }

    private fun PreparedStatement.ponce(f:PreparedStatement.()->Unit){
        try {
            this.f()
            conn.commit()
        }catch(e:Exception){
            e.printStackTrace()
            conn.rollback()
            this.close()
        }finally{
        }
    }

    fun save(entity:HttpEntity):HttpEntity{
        val pstmt = conn.prepareStatement("""
            INSERT INTO HTTP_ENTITY (METHOD,HOST,URL,CONTENT_TYPE,CONTENT_ENCODING,STATUS,LENGTH,START_TIME,REQUEST_HEADER,RESPONSE_HEADER,CONTENT)
            VALUES(?,?,?,?,?,?,?,?,?,?,?)
        """)!!

        pstmt.ponce{
            var i = 1;
            setString(i++, entity.method)
            setString(i++, entity.host)
            setString(i++, entity.url)
            setString(i++, entity.contentType)
            setString(i++, entity.contentEncoding)
            setInt(i++, entity.status)
            setLong(i++, entity.length)
            setTimestamp(i++, Timestamp(entity.startTime.getTime()))
            setCharacterStream(i++, StringReader(entity.requestHeader))
            setCharacterStream(i++, StringReader(entity.responseHeader))
            if (entity.content != null) {
                setBinaryStream(i, entity.content!!.inputStream)
            } else {
                setBinaryStream(i, ByteArray(0).inputStream)
            }
            executeUpdate()

            entity.id = getGeneratedKeys()!! let { rs ->
                rs.next()
                rs.getLong(1)
            }
        }

        return entity
    }

    fun asset(rs:ResultSet):HttpEntity{
        val he = HttpEntity()
        he.id = rs.getLong("ID")
        he.method = rs.getString("METHOD")!!
        he.host = rs.getString("HOST")!!
        he.url = rs.getString("URL")!!
        he.contentType = rs.getString("CONTENT_TYPE")!!
        he.status = rs.getInt("STATUS")
        he.length = rs.getLong("LENGTH")
        he.startTime = rs.getTimestamp("START_TIME")!!
        he.contentEncoding = rs.getString("CONTENT_ENCODING")!!
        he.requestHeader = rs.getString("REQUEST_HEADER")!!
        he.responseHeader = rs.getString("RESPONSE_HEADER")!!
        he.content = rs.getBinaryStream("CONTENT")!!.readBytes()

        return he
    }

    fun find(where:String):List<HttpEntity> {
        var stmt = conn.createStatement()!!
        val list = arrayListOf<HttpEntity>()
        stmt.once{
            executeQuery(""" SELECT * FROM HTTP_ENTITY ${ where } """) me {
                while(next()){
                    list.add(asset(this))
                }
            }
        }
        return list
    }

}
