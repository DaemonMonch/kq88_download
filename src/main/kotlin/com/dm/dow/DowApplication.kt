package com.dm.dow

import com.dm.dow.DowApplication.Companion.log
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import kotlinx.coroutines.*
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
import java.net.URI
import java.nio.file.Paths
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory

@RestController
@SpringBootApplication
@Configuration
class DowApplication(val webClient: WebClient) {

    companion object {
        @JvmStatic
        val log = LoggerFactory.getLogger(javaClass)
        @JvmStatic
        val XML_PARTTERN = ".*?record[0-9]*\\.xml".toRegex()
        @JvmStatic
        val dbFactory = DocumentBuilderFactory.newInstance()
    }

    @Value("\${spring.resources.static-locations}")
    lateinit var staticLocation: String

    val downloader = Downloader(webClient)

    @PostMapping("/down")
    suspend fun download(@RequestBody list: DownList): String {
        val xmlUrl = list.data.find { it.contains(XML_PARTTERN) }
                ?: throw IllegalArgumentException("do not have flash xml")
        val m3u8Url = list.data.find { it.endsWith(".m3u8") }
                ?: throw IllegalArgumentException("do not have flash xml")
        val pptBaseUrl = xmlUrl.substring(0, xmlUrl.lastIndexOf("/"))
        val m3u8BaseUrl = m3u8Url.substring(0, m3u8Url.lastIndexOf("/"))
        val m3u8 = Disp.DOWN.invoke {
            webClient.get().uri("$m3u8BaseUrl/record.m3u8").retrieve().awaitBody<String>()
        }
        val recordXML = Disp.DOWN.invoke {
            webClient.get().uri(xmlUrl).retrieve().awaitBody<String>()
        }
        val subPath = Paths.get(URI.create(m3u8BaseUrl).path).parent.fileName.toString()
        val basePath = staticLocation.substring(staticLocation.indexOf(":") + 1)
        val dir = File(basePath, subPath)
        log.info(dir.toString())
        if (dir.exists())
            dir.deleteRecursively()

        dir.mkdir()

        val data = m3u8.lines()
                .filter { !it.startsWith("#") && it.isNotBlank()}
                .map { if (!it.startsWith("http")) "$m3u8BaseUrl/$it" else it }
        val name = list.name
        log.info("start download $name")
        val ppts = parsePpt(recordXML)
        val downloadVideos = data.map { Download(File(dir, it.substring(it.lastIndexOf("/") + 1)), it) }
        val downloadPpts = ppts.map { Download(File(dir,it.newName),"$pptBaseUrl/${it.name}") }
        downloader.download(dir, downloadVideos + downloadPpts)
        mergeTS(name, dir)
        val finalFile = File(basePath, name)
        if(finalFile.exists())
            finalFile.deleteRecursively()
        dir.renameTo(finalFile)
//        return """http://dsp.eyangmedia.com/kq88/$subPath/${merged.name}"""
        return "ok"

    }

    @PostMapping("/flashdown")
    suspend fun downloadFlash(@RequestBody list: DownList): String {
        val xmlUrl = list.data.find { it.contains(XML_PARTTERN) }
                ?: throw IllegalArgumentException("do not have flash xml")
        val baseUrl = xmlUrl.substring(0, xmlUrl.lastIndexOf("/"))
        val recordXML = Disp.DOWN.invoke {
            webClient.get().uri(xmlUrl).retrieve().awaitBody<String>()
        }

        val subPath = Paths.get(URI.create(xmlUrl).path).parent.fileName.toString()
        val basePath = staticLocation.substring(staticLocation.indexOf(":") + 1)
        val dir = File(basePath, subPath)
        log.info(dir.toString())
        if (dir.exists())
            dir.deleteRecursively()

        dir.mkdir()

        val videoNames = parseVideoName(recordXML)
        val ppts = parsePpt(recordXML)
        val name = list.name
        log.info("start download $name")
        val downloadVideos = videoNames.map { Download(File(dir, it), "$baseUrl/$it") }
        val downloadPpts = ppts.map { Download(File(dir,it.newName),"$baseUrl/${it.name}") }
        downloader.download(dir, downloadVideos + downloadPpts)
        val merged = mergeFlash(name, dir, videoNames)
        val finalFile = File(basePath, name)
        if(finalFile.exists())
            finalFile.deleteRecursively()
        dir.renameTo(finalFile)
//        return """http://dsp.eyangmedia.com/kq88/$subPath/${merged.name}"""
        return "ok"

    }

    fun parseVideoName(xml: String): List<String> =
            parseXml(xml, "/conf//multirecord") {
                val list = mutableListOf<String>()
                for (i in 0 until it.length) {
                    val n = it.item(i)
                    list.add(n.attributes.getNamedItem("multimedia").nodeValue)
                }
                list
    }

    fun parsePpt(xml:String) : List<PPTInfo> =
            parseXml(xml,"/conf//document/page"){
                val list = mutableListOf<PPTInfo>()
                for (i in 0 until it.length) {
                    val n = it.item(i)
                    val attributes = n.attributes
                    list.add(PPTInfo(attributes.getNamedItem("id").nodeValue.toInt(),
                            attributes.getNamedItem("content").nodeValue))
                }
                list
            }

    fun <T> parseXml(xml: String, xpath: String, mapper: (NodeList) -> T): T {
        val dBuilder = dbFactory.newDocumentBuilder()
        val xmlInput = InputSource(StringReader(xml))
        val xpFactory = XPathFactory.newInstance()
        val xPath = xpFactory.newXPath()
        val nodes = xPath.evaluate(xpath, dBuilder.parse(xmlInput), XPathConstants.NODESET) as NodeList
        return mapper(nodes)
    }

    private suspend fun mergeTS(name: String, dir: File, dst: File = dir): File = withContext(Disp.MERGE) {
        log.info("merge $name")
        val partialNames = dir.list()
        val tss = partialNames.filter { it.endsWith(".ts") }.sortedBy { it.substring(it.indexOf("_") + 1, it.indexOf("-")).toInt() }
        val merged = File(dst, name + ".ts")
        val raf = FileOutputStream(merged, true)
        raf.use { out ->
            tss.forEach {

                val pf = File(dir, it)
                val pfacf = RandomAccessFile(pf, "r")
                pfacf.use { io ->
                    io.channel.transferTo(0, pfacf.length(), out.channel)
                }
                pf.delete()
            }
        }

        zipFiles(File(dst,"ppt.zip"),dir.listFiles().filter { it.name.endsWith(".swf") })

        return@withContext merged
    }

    fun zipFiles(dst:File, files:List<File>, deleted:Boolean = true) {
        dst.outputStream().use { os ->
            val gzipOutputStream = ZipOutputStream(os)
            gzipOutputStream.use { gos ->
                files.forEach { ppt ->
                    gos.putNextEntry(ZipEntry(ppt.name))
                    ppt.inputStream().use { io -> io.copyTo(gos) }
                    if(deleted)
                        ppt.delete()
                }

            }

        }
    }

    //rename
    suspend fun mergeFlash(name: String, dir: File,filenames:List<String>,dst: File = dir): File = withContext(Disp.MERGE) {
        log.info("merge $name")
        val ppts = dir.listFiles().filter{it.name.endsWith(".swf") }
        val pptsGizp = File(dst, "ppt.zip")
        zipFiles(pptsGizp,ppts)

        val videosZip = File(dst,"video.zip")
        videosZip.outputStream().use { os ->
            val gzipOutputStream = ZipOutputStream(os)
            gzipOutputStream.use { gos ->

                filenames.forEachIndexed { i, filename ->
                    val pf = File(dir, filename)
                    if(pf.exists()){
                        gos.putNextEntry(ZipEntry("${i}.flv"))
                        pf.inputStream().use {  it.copyTo(gos) }
                        pf.delete()
                    }

//                    pf.renameTo(File(dir, "${i}.flv"))

                }
            }

        }

        return@withContext dir
    }
}

suspend fun <T> retry(retryTimes: Int = 3, fn: suspend () -> T): T {
    try {
        return fn()
    } catch (e: Exception) {
        if (retryTimes == 0 || e is CancellationException)
            throw e
        log.info("${e.message} fail ${retryTimes - 1} retries ")
        return retry(retryTimes - 1, fn)
    }
}


fun main(args: Array<String>) {
    runApplication<DowApplication>(*args)
}

