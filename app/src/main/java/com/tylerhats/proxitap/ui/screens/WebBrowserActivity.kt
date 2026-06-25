package com.tylerhats.proxitap.ui.screens

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.tylerhats.proxitap.ui.theme.ProxiTapTheme

class WebBrowserActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ProxiTapTheme {
                WebBrowserScreen(
                    onCloseClick = { finish() }
                )
            }
        }
    }
}
