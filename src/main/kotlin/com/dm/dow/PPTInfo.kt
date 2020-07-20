package com.dm.dow

data class PPTInfo (val id:Int,val name:String) {
    val newName = id.toString() +  name.substring(name.lastIndexOf("."))
}