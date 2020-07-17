package com.dm.dow

import com.dm.dow.DowApplication.Companion.log
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.web.bind.annotation.*
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody
import org.w3c.dom.NodeList
import org.xml.sax.InputSource
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.io.StringReader
import java.lang.Exception
import java.lang.RuntimeException
import java.net.URI
import java.nio.file.Paths
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory

@RestController
@SpringBootApplication
@Configuration
class DowApplication(val webClient: WebClient) {

    companion object {
        @JvmStatic val log = LoggerFactory.getLogger(javaClass)
    }

    @Value("\${spring.resources.static-locations}")
    lateinit var staticLocation:String

    val downloader = Downloader(webClient)

    @PostMapping("/down")
    suspend fun download(@RequestBody list:DownList) : String {
        val firstUrl = list.data.first()
        val baseUrl = firstUrl.substring(0,firstUrl.lastIndexOf("/"))
        val m3u8 = Disp.DOWN.invoke { webClient.get().uri("$baseUrl/record.m3u8").retrieve().awaitBody<String>()
        }
        val subPath =  Paths.get(URI.create(firstUrl).path).parent.parent.fileName.toString()
        val basePath = staticLocation.substring(staticLocation.indexOf(":") + 1)
        val dir = File(basePath , subPath)
        log.info(dir.toString())
        if(dir.exists())
            dir.deleteRecursively()

            dir.mkdir()

        val data = m3u8.lines()
                .filter{!it.startsWith("#")}
                .map{if(!it.startsWith("http"))"$baseUrl/$it" else it}
        val name = list.name
        log.info("start download $name")

        downloader.download(dir,data)
        val merged = mergeTS(name,dir,dir.parentFile)
        return """http://dsp.eyangmedia.com/kq88/$subPath/${merged.name}"""


    }

    @PostMapping("/flashdown")
    suspend fun downloadFlash(@RequestBody list:DownList) : String {
        val xmlUrl = list.data.find { it.contains(".*?record[0-9]*\\.xml".toRegex()) } ?: throw IllegalArgumentException("do not have flash xml")
        val baseUrl = xmlUrl.substring(0,xmlUrl.lastIndexOf("/"))
        val recordXML = Disp.DOWN.invoke { webClient.get().uri(xmlUrl).retrieve().awaitBody<String>()
        }

        val subPath =  Paths.get(URI.create(xmlUrl).path).parent.fileName.toString()
        val basePath = staticLocation.substring(staticLocation.indexOf(":") + 1)
        val dir = File(basePath , subPath)
        log.info(dir.toString())
        if(dir.exists())
            dir.deleteRecursively()

        dir.mkdir()

        val data = parseVideoName(recordXML)
        val name = list.name
        log.info("start download $name")
        downloader.download(dir,data.map{"$baseUrl/$it"})
        val merged = mergeFlash(name,dir,data)
        return """http://dsp.eyangmedia.com/kq88/$subPath/${merged.name}"""

    }

    fun parseVideoName(xml:String):List<String> {
        val dbFactory = DocumentBuilderFactory.newInstance()
        val dBuilder = dbFactory.newDocumentBuilder()
        val xmlInput = InputSource(StringReader(xml))
        val xpFactory = XPathFactory.newInstance()
        val xPath = xpFactory.newXPath()
        val xpath = "/conf//multirecord"
        val nodes = xPath.evaluate(xpath, dBuilder.parse(xmlInput), XPathConstants.NODESET) as NodeList
        val list = mutableListOf<String>()
        for (i in 0 until nodes.length) {
            val n = nodes.item(i)
            list.add(n.attributes.getNamedItem("multimedia").nodeValue)
        }
        return  list
    }

    private suspend fun mergeTS(name: String, dir: File,dst :File = dir):File = withContext(Disp.MERGE){
        log.info("merge $name")
        val partialNames = dir.list()
        partialNames.sortBy { it.substring(it.indexOf("_") + 1,it.indexOf("-")).toInt() }
        val merged = File(dir.parentFile,name + ".ts")
        val raf = FileOutputStream(merged,true)
        raf.use { out ->
            partialNames.forEach {

                val pf = File(dir,it)
                val pfacf = RandomAccessFile(pf,"r")
                pfacf.use { io ->
                    io.channel.transferTo(0,pfacf.length(),out.channel)
                }
            }
        }

        return@withContext merged
    }

    //rename
    suspend fun mergeFlash(name: String, dir: File,filenames:List<String>) : File = withContext(Disp.MERGE) {
        log.info("merge $name")
        val merged = File(dir.parentFile,name)
        filenames.forEachIndexed { i,filename ->
            val pf = File(dir,filename)
            pf.renameTo(File(dir,"${i}.flv"))
        }
        if(merged.exists())
            merged.deleteRecursively()
        dir.renameTo(merged)
        return@withContext merged
    }
}

suspend fun <T> retry(retryTimes : Int = 3,fn : suspend () -> T) : T {
    try{
        return fn()
    }catch (e:Exception) {
        if(retryTimes == 0 || e is CancellationException)
            throw e
        log.info("${e.message} fail ${retryTimes - 1} retries ")
        return retry(retryTimes - 1,fn)
    }
}


data class DownList(var name:String = "",var data:Set<String> = emptySet())

@ControllerAdvice
class ExHandler {

    @ExceptionHandler
    @ResponseBody
    fun handle(e:Exception) : Any {
        e.printStackTrace()
        return mapOf("error" to e.message)
    }
}


fun main(args: Array<String>)  {
	runApplication<DowApplication>(*args)
}

