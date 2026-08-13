package com.zoya.ai.assistant

import android.os.Bundle
import com.getcapacitor.BridgeActivity

class MainActivity : BridgeActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        registerPlugin(ZoyaBridgePlugin::class.java)
        super.onCreate(savedInstanceState)
    }
}
