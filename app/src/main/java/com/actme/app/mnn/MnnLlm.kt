package com.actme.app.mnn

object MnnLlm {
    external fun nativeGetVersion(): String

    fun getVersion(): String = nativeGetVersion()

    init {
        System.loadLibrary("actme_jni")
    }
}
