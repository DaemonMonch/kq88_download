package com.dm.dow

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Semaphore
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody
import java.io.File
import java.lang.Exception


class Downloader (val webClient: WebClient) {

    companion object {
        @JvmStatic val log = LoggerFactory.getLogger(javaClass)
    }

    val semaphore = Semaphore(4)


    suspend fun download(dir:File,urls:List<String>) : File = withContext(Disp.DOWN){
        supervisorScope {
            val tasks = urls.map {url ->
                val task = launch(CoroutineExceptionHandler{_,ex ->
                    log.error("",ex.message)
                    semaphore.release()
                })  {
                    semaphore.acquire()
                    val resp = retry {
                        log.info("downloading $url")
                        webClient.get().uri(url).retrieve().awaitBody<ByteArray>()
                    }
                    val file = File(dir,url.substring(url.lastIndexOf("/") + 1))
                    if(file.exists())
                        file.delete()
                    file.createNewFile()
                    resp.inputStream().use { inp ->
                        file.outputStream().use { out ->
                            inp.copyTo(out)
                        }
                    }
                    semaphore.release()
                }

                task
            }

            joinAll(*(tasks.toTypedArray()))
        }


        return@withContext dir
    }
}