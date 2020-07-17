package com.dm.dow

import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseBody
import java.lang.Exception

@ControllerAdvice
class ExHandler {

    @ExceptionHandler
    @ResponseBody
    fun handle(e: Exception): Any {
        e.printStackTrace()
        return mapOf("error" to e.message)
    }
}