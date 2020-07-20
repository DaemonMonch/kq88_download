package com.dm.dow

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import org.slf4j.LoggerFactory
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody
import java.io.File


class Downloader (val webClient: WebClient) {

    companion object {
        @JvmStatic val log = LoggerFactory.getLogger(javaClass)
    }

    val semaphore = Semaphore(4)

    suspend fun download(dir:File,downloads: Iterable<Download>) : File = withContext(Disp.DOWN) {
        supervisorScope {
            val tasks = downloads.map {down ->
                val task = launch(CoroutineExceptionHandler{_,ex ->
                    log.error("",ex.message)
                    semaphore.release()
                })  {
                    semaphore.acquire()
                    val resp = retry {
                        log.info("downloading ${down.url}")
                        webClient.get().uri(down.url).retrieve().awaitBody<ByteArray>()
                    }
                    //val file =
                    val file = down.downFile
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

data class Download(val downFile: File, val url:String)